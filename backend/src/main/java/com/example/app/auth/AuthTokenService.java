package com.example.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.common.exception.InvalidRequestException;
import com.example.app.user.User;

/**
 * 使い捨てトークンの発行と検証。メール確認とパスワードリセットで共用する。
 *
 * <p>生の値はメールの URL にだけ載り、DB には SHA-256 ハッシュを保存する(設計の決定6)。
 *
 * <p><b>なぜパスワードと違って BCrypt ではなく SHA-256 なのか</b>: ① トークンは 32 バイトの
 * ランダム値なので総当たりが不可能で、BCrypt のような意図的に遅いハッシュが要らない
 * ② 受け取った値からハッシュを計算して UNIQUE index で 1 行引く必要があり、同じ入力から
 * 常に同じ出力になる決定的なハッシュでなければならない(BCrypt は毎回ソルトが変わるので引けない)。
 */
@Service
public class AuthTokenService {

	/** メール確認リンクの有効期限。 */
	static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);

	/** パスワードリセットリンクの有効期限。メール確認より短くしている(乗っ取り時の影響が大きいため)。 */
	static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

	// 32 バイト = 256 ビット。Base64URL にすると 43 文字になる。
	private static final int TOKEN_BYTES = 32;

	// SecureRandom は暗号用途の乱数。Math.random() や java.util.Random は予測可能なので使わない。
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	// URL に載せるので Base64URL(記号が - と _ だけ)を使い、末尾の = 埋めも外す。
	private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final AuthTokenRepository authTokenRepository;

	AuthTokenService(AuthTokenRepository authTokenRepository) {
		this.authTokenRepository = authTokenRepository;
	}

	/**
	 * トークンを発行し、<b>生の値</b>を返す。DB にはハッシュだけを保存するので、この戻り値を
	 * メールに載せ損ねると誰も使えなくなる(あとから取り出す手段はない)。
	 *
	 * <p>同じ用途の未使用トークンは無効化する。有効なリンクが同時に複数存在しないようにするため。
	 */
	@Transactional
	public String issue(User user, AuthTokenPurpose purpose, Duration validFor) {
		//同じ用途の未使用トークンを使用済みにする
		invalidateUnused(user, purpose);

		//トークンを発行し、<b>生の値</b>を返す
		String rawToken = URL_ENCODER.encodeToString(randomBytes());

		//DB にはハッシュだけを保存する
		authTokenRepository.save(new AuthToken(user, sha256Hex(rawToken), purpose, LocalDateTime.now().plus(validFor)));
		return rawToken;
	}

	/**
	 * トークンを検証し、使用済みにして、紐づくユーザーを返す。
	 *
	 * <p>不正・期限切れ・使用済みはすべて 400 になる。用途違いのトークン(リセット用のリンクで
	 * メール確認しようとした等)は「存在しない」と同じメッセージにする。存在を教えないため。
	 */
	@Transactional
	public User consume(String rawToken, AuthTokenPurpose purpose) {
		AuthToken token = authTokenRepository.findByToken(sha256Hex(rawToken))
				.orElseThrow(() -> new InvalidRequestException("リンクが不正です。もう一度やり直してください"));

		if (token.getPurpose() != purpose) {
			throw new InvalidRequestException("リンクが不正です。もう一度やり直してください");
		}
		if (token.getUsedAt() != null) {
			throw new InvalidRequestException("このリンクは既に使用されています");
		}
		if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new InvalidRequestException("リンクの有効期限が切れています。もう一度やり直してください");
		}

		token.markUsed(LocalDateTime.now());
		return token.getUser();
	}

	/** 同じ用途の未使用トークンを使用済みにする。パスワード変更時に発行済みリンクを失効させるのにも使う。 */
	@Transactional
	public void invalidateUnused(User user, AuthTokenPurpose purpose) {
		LocalDateTime now = LocalDateTime.now();
		authTokenRepository.findByUserAndPurposeAndUsedAtIsNull(user, purpose)
		//usedAt プロパティに日時を入れて使用済み扱いにする
				.forEach(token -> token.markUsed(now));
		//なぜ save() を呼んでいないのに DB が更新されるのか
		// ここが初見でいちばん引っかかる部分です。issue(62行目)では authTokenRepository.save(...) を呼んでいるのに、invalidateUnused では保存処理が一切ありません。それでも DB は更新されます。
		// これは JPA のダーティチェックという仕組みによります。
		// 1. findBy... で取得したエンティティは、JPA の管理下(永続化コンテキスト)に置かれます。JPA はこのとき取得時点の値を控えておきます
		// 2. markUsed でプロパティを書き換えます
		// 3. トランザクションが終わる(= @Transactional のメソッドを抜ける)ときに、JPA が控えた値と現在の値を突き合わせ、変わっているプロパティを見つけて自動で UPDATE 文を発行します
		// 「DB から取り出したオブジェクトは、DB の行とつながったまま」というイメージです。
		// だから代入するだけで DB に反映されます。逆に言うと、@Transactional を外すとこの仕組みが働く区間が無くなり、変更が DB に届かなくなります。
		// ここが @Transactional のもう1つの、そしてより重要な役割です。
		//forEach で1件ずつ書き換えるので、N 件あれば N 回の UPDATE になります。@Modifying を付けた一括更新クエリで1回にする手もあります。
	}

	private byte[] randomBytes() {
		//new byte[TOKEN_BYTES] は、要素が 32 個の配列を新しく作ります。
		// Java では数値の配列を作った時点で全要素が 0 で初期化されるので、この時点の中身は 0 が 32 個並んだ状態です。
		byte[] bytes = new byte[TOKEN_BYTES];
		SECURE_RANDOM.nextBytes(bytes);
		return bytes;
	}

	private String sha256Hex(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 は Java の標準実装に必ず含まれるため、ここには到達しない。
			throw new IllegalStateException("SHA-256 が利用できません", e);
		}
	}
}
