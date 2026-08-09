package com.example.app.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.app.auth.dto.ChangePasswordRequest;
import com.example.app.auth.dto.MeResponse;
import com.example.app.auth.dto.PasswordResetConfirmRequest;
import com.example.app.auth.dto.PasswordResetRequest;
import com.example.app.auth.dto.ResendVerificationRequest;
import com.example.app.auth.dto.SignupRequest;
import com.example.app.auth.dto.VerifyEmailRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 認証まわりの API の入口。
 *
 * <p>ログイン({@code POST /api/auth/login})とログアウト({@code POST /api/auth/logout})は
 * このクラスに無い。Spring Security の formLogin / logout がフィルタとして処理するため、
 * 対応する Controller メソッドが存在しない。設定は {@code config/SecurityConfig} を参照。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/**
	 * 現在ログインしているユーザーを返す。認証不要で、未ログインなら 200 + {@code user: null}。
	 *
	 * <p>フロントはアプリ起動時にこれを 1 回呼んでログイン状態を復元する。
	 * このレスポンスと一緒に XSRF-TOKEN Cookie も発行されるため、
	 * 「ログイン前なので CSRF トークンが無くログインできない」問題もここで解消される。
	 *
	 * <p>principal は未ログインのとき null になる(→ {@link AppUserDetails} のコメント)。
	 */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal AppUserDetails principal) {
		return authService.getCurrentUser(principal == null ? null : principal.getUserId());
	}

	/**
	 * 会員登録。未確認ユーザーを作り、確認メールを送る。
	 *
	 * <p>この時点ではログインできない(メール確認が済んでいないため)。レスポンスに中身は返さず、
	 * フロントは「確認メールを送りました」の表示に切り替える。
	 */
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public void signup(@Valid @RequestBody SignupRequest request) {
		authService.signup(request);
	}

	/** メールのリンクから渡されたトークンでメール確認を完了させる。 */
	@PostMapping("/verify-email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		authService.verifyEmail(request.token());
	}

	/** 確認メールの再送。メールが届かなかった / 有効期限が切れた場合の復帰経路。 */
	@PostMapping("/verification/resend")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		authService.resendVerification(request.email());
	}

	/** パスワードリセットの申請。リセット用リンクをメールで送る。未ログインで使う。 */
	@PostMapping("/password-reset/request")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
		authService.requestPasswordReset(request.email());
	}

	/** パスワードリセットの実行。完了するとそのユーザーの全セッションが消える(全端末強制ログアウト)。 */
	@PostMapping("/password-reset/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
		authService.confirmPasswordReset(request.token(), request.newPassword());
	}

	/**
	 * ログイン中のパスワード変更。<b>このアプリで唯一の認証必須エンドポイント</b>
	 * (投稿の作成・削除がステップ5 で加わる)。
	 *
	 * <p>操作中のセッション以外を消すため、現在のセッション ID を Service に渡す。
	 * HttpServletRequest を触るのは Controller の側に留め、Service は Web 層の型を受け取らない。
	 */
	//@AuthenticationPrincipal で、ログイン中のユーザー情報（AppUserDetails）を受け取る
	//HttpServletRequest httpRequest はサーブレット（Java の Web の土台となる仕組み）が扱う「HTTP リクエストそのもの」です。
	// ヘッダ、Cookie、セッションなど、リクエストに紐づく低レベルの情報を全部持っています。Spring では引数に書くだけで受け取れます。
	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
			@AuthenticationPrincipal AppUserDetails principal, HttpServletRequest httpRequest) {
		// getSession(false) … 無ければ作らずに null を返す(引数なしの getSession() は作ってしまう)。
		//Controller の getSession(false) で SQL は走っていない。 セッションはリクエスト内で 1 回だけ読んでキャッシュされる。
		// Spring Security が Controller のずっと手前で読み込み済みなので、
		// AuthController.java の getSession(false) はキャッシュを返すだけ。SELECT が 2 回走ることはない。
		HttpSession session = httpRequest.getSession(false);
		authService.changePassword(principal.getUserId(), request.currentPassword(), request.newPassword(),
				session == null ? null : session.getId());
	}
}
