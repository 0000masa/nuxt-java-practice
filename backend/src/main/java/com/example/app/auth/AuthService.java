package com.example.app.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.auth.dto.MeResponse;
import com.example.app.auth.dto.SignupRequest;
import com.example.app.common.exception.FieldValidationException;
import com.example.app.common.exception.InvalidRequestException;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * 認証にまつわる業務ロジック。
 *
 * <p>ログイン・ログアウトそのものは Spring Security の formLogin / logout が担当するので
 * このクラスには無い(→ docs/adr/0002-session-cookie-over-jwt.md)。
 * ここにはフレームワークが用意していない処理(会員登録・メール確認・パスワードリセット)が入る。
 */
@Service
public class AuthService {

	private final UserRepository userRepository;
	private final AuthTokenService authTokenService;
	private final UserSessionManager userSessionManager;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationEventPublisher eventPublisher;

	public AuthService(UserRepository userRepository, AuthTokenService authTokenService,
			UserSessionManager userSessionManager, PasswordEncoder passwordEncoder,
			ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.authTokenService = authTokenService;
		this.userSessionManager = userSessionManager;
		this.passwordEncoder = passwordEncoder;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * 現在ユーザーを返す。userId が null(未ログイン)なら user: null のレスポンスになる。
	 *
	 * <p>principal に表示名を持たせず毎回 DB から読むのは、プロフィール編集(フェーズ7)後に
	 * セッション内の値が古くならないようにするため。
	 */
	@Transactional(readOnly = true)
	public MeResponse getCurrentUser(Long userId) {
		if (userId == null) {
			return MeResponse.anonymous();
		}
		return userRepository.findById(userId)
		// ここの .map() は 配列の map ではなく Optional の map
		//.map() は「中身があればその値に処理を適用する。空なら何もせず空のまま返す」という意味
		//List.stream().map() は 0 個以上に対して働き、Optional.map() は最大 1 個です。今回のように findById の直後に付いていたら、それは Optional のほうです。戻り値の型を見るのが確実な見分け方です。
				.map(MeResponse::of)
				// セッションは生きているが users の行が消えている場合(削除されたアカウント)。
				// 未ログインと同じ扱いにする。
				//.orElseGet(...) は「箱が空だったときの代替値を作る」という意味です。「中身があればそれを取り出す。無ければ引数で渡した処理を実行して、その結果を返す」
				.orElseGet(MeResponse::anonymous);
	}

	/**
	 * 会員登録。未確認ユーザーを作り、確認メールの送信を予約する。
	 *
	 * <p>メールアドレスが既に使われているときの振る舞いが 2 つに分かれる
	 * (詳細と理由 → docs/adr/0003-account-enumeration-and-unverified-signup.md)。
	 * <ul>
	 * <li><b>確認済み</b> … 400 + fieldErrors.email で弾く</li>
	 * <li><b>未確認</b> … 既存の行を削除して、今回の入力で作り直す</li>
	 * </ul>
	 */
	@Transactional
	public void signup(SignupRequest request) {
		Optional<User> existingByEmail = userRepository.findByEmail(request.email());

		// 確認済みのアカウントは上書きしない。
		existingByEmail.filter(user -> user.getEmailVerifiedAt() != null).ifPresent(user -> {
			throw new FieldValidationException("email", "このメールアドレスは既に登録されています");
		});
		// ここを通った時点で、existingByEmail があるならそれは未確認のアカウント。

		// username の重複は、未確認アカウントの作り直しより先に検証する。
		// filter は「これから消す予定の行(同じメールアドレスの未確認アカウント)自身」を除くためのもの。
		// 自分が今使っている username をそのまま指定し直しただけなら、重複ではない。
		userRepository.findByUsername(request.username())
		//未確認アカウントとユーザー名が被っていてもどちらにしろ作り直すから問題ない
		//他人の未確認アカウントが alice を使っている場合は、alice を使おうとすると重複エラーになる
				.filter(owner -> existingByEmail.isEmpty() || !owner.getId().equals(existingByEmail.get().getId()))
				.ifPresent(owner -> {
					throw new FieldValidationException("username", "このユーザー名は既に使われています");
				});

		existingByEmail.ifPresent(unverified -> {
			// 未確認アカウントを作り直す本当の狙いは「古い資格情報を破棄すること」。
			// 確認メールを送るだけにすると、攻撃者が先に他人のメールアドレスで登録しておき、
			// 本人が後から登録して確認リンクを踏んだ瞬間に、攻撃者のパスワードのアカウントが
			// 有効化される(pre-hijacking)。→ docs/adr/0003
			// これを「危険な実装」と見て削除をやめると、その脆弱性が戻ってくる。
			userRepository.delete(unverified);
			// flush を明示しているのは順序を確定させるため。JPA は SQL の発行順を自分で決めるので、
			// これが無いと INSERT が先に走り、メールアドレスの UNIQUE 制約に当たって落ちることがある。
			userRepository.flush();
			//flush が自動で起こるのは主に次の 2 つです。
			// - トランザクションのコミット直前（このメソッドなら 114 行目を抜けた後）
			// - JPQL などのクエリを実行する直前（溜まった変更を反映しないと検索結果が嘘になるため）
			// userRepository.flush() は、これを手動で今やるメソッドです。
		});

		User user = new User(request.username(), request.displayName(), request.email());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		userRepository.save(user);

		issueVerificationMail(user);
	}

	/** メール確認を完了させる。トークンが不正・期限切れ・使用済みなら 400。 */
	@Transactional
	public void verifyEmail(String rawToken) {
		//トークンを検証し、使用済みにして、紐づくユーザーを返す
		User user = authTokenService.consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION);
		if (user.getEmailVerifiedAt() == null) {
			// 取得したユーザーは JPA の管理下にあるので、値を変えるだけで UPDATE が流れる。
			user.setEmailVerifiedAt(LocalDateTime.now());
		}
	}

	/** 確認メールを再送する。古い未使用トークンは AuthTokenService.issue の中で無効化される。 */
	@Transactional
	public void resendVerification(String email) {
		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new FieldValidationException("email", "このメールアドレスは登録されていません"));
		if (user.getEmailVerifiedAt() != null) {
			throw new InvalidRequestException("メールアドレスの確認は既に完了しています。そのままログインしてください");
		}
		issueVerificationMail(user);
	}

	/**
	 * パスワードリセットの申請。リセット用リンクをメールで送る。
	 *
	 * <p>未確認のアカウントには許可しない。許すと「メール確認を経ずにパスワードを設定して
	 * ログインできる」抜け道になり、メール確認の意味がなくなる(→ docs/adr/0003)。
	 *
	 * <p>逆に「メールは確認済みだがパスワードが未設定」のアカウント(フェーズ4 の Google 専用ユーザーや
	 * dev_user)には許可する。この経路がパスワードを新規に設定する手段になる。
	 */
	@Transactional
	public void requestPasswordReset(String email) {
		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new FieldValidationException("email", "このメールアドレスは登録されていません"));
		if (user.getEmailVerifiedAt() == null) {
			throw new InvalidRequestException("メールアドレスの確認が完了していません。先に確認メールから有効化してください");
		}
		//トークンを発行し、<b>生の値</b>を返す。DB にはハッシュだけを保存する
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.PASSWORD_RESET,
				AuthTokenService.PASSWORD_RESET_TTL);
		eventPublisher.publishEvent(
				new AuthMailRequestedEvent(user.getEmail(), AuthTokenPurpose.PASSWORD_RESET, rawToken)
			);
	}

	/**
	 * パスワードリセットの実行。新しいパスワードを設定し、<b>そのユーザーの全セッションを消す</b>。
	 *
	 * <p>セッションを消すのがこの機能の目的の半分。「パスワードを盗まれたかもしれないから
	 * リセットした」のに攻撃者のセッションが生き残っていては意味がない(設計の決定11)。
	 *
	 * <p>この操作は未ログイン状態で行うので、消して困る自分のセッションは無い。
	 */
	@Transactional
	public void confirmPasswordReset(String rawToken, String newPassword) {
		//トークンを検証し、使用済みにして、紐づくユーザーを返す
		User user = authTokenService.consume(rawToken, AuthTokenPurpose.PASSWORD_RESET);

		//ダーティチェッキング（変更検知）によりsave() や UPDATE が出てこないのに DB が更新される
		user.setPasswordHash(passwordEncoder.encode(newPassword));

		//そのユーザーの全セッションを消す。パスワードリセット完了時に使う(全端末が強制ログアウトされる)
		userSessionManager.deleteAll(user.getEmail());
	}

	/**
	 * ログイン中のパスワード変更。現在のパスワードを確認したうえで差し替える。
	 *
	 * <p>リセットと違い、操作中のセッションだけは残す。変更した本人が追い出されるのは不自然なため。
	 *
	 * @param currentSessionId 残すセッション。Controller が HttpServletRequest から取って渡す
	 */
	@Transactional
	public void changePassword(Long userId, String currentPassword, String newPassword, String currentSessionId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new InvalidRequestException("ユーザーが見つかりません"));

		// パスワード未設定のユーザーはこの経路では変えられない(照合する現在のパスワードが無い)。
		// その場合はパスワードリセットの経路で設定する。
		if (user.getPasswordHash() == null
				|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new FieldValidationException("currentPassword", "現在のパスワードが違います");
		}

		//@Transactional の中で JPA の管理下にあるオブジェクトを変更しているので、save() を呼ばなくてもコミット時に UPDATE が流れます（ダーティチェッキング）
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		// 発行済みのリセットリンクも失効させる。パスワードを変えた後に古いリセットメールが
		// 使えてしまうと、変更前の状態に戻せることになる。
		authTokenService.invalidateUnused(user, AuthTokenPurpose.PASSWORD_RESET);
		////過去のセッションは、パスワードを変えても自動では無効になりません。有効期限が切れないと無効にならない。
		//そのユーザーのセッションのうち、指定したもの以外を消す。攻撃者がすでに持っているログイン状態が生き残ることを防ぐ。
		userSessionManager.deleteAllExcept(user.getEmail(), currentSessionId);
	}

	private void issueVerificationMail(User user) {
		//トークンを発行し、<b>生の値</b>を返す
		//同じ用途の未使用トークンは無効化する
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				AuthTokenService.EMAIL_VERIFICATION_TTL);
		// 送信自体はこのトランザクションのコミット後に行われる(→ AuthMailSender)。
		eventPublisher.publishEvent(
				new AuthMailRequestedEvent(user.getEmail(), AuthTokenPurpose.EMAIL_VERIFICATION, rawToken)
			);
	}
}
