package com.example.app.auth;

/**
 * Google ログインを受け付けられないときに投げる例外。
 *
 * <p>共通例外({@code common.exception})に置いていないのは、これが
 * <b>Controller に到達する前のフィルタ段階</b>で起きるものだから。GlobalExceptionHandler は
 * 通らないので、{@link AppOidcUserService} が Spring Security の
 * {@code OAuth2AuthenticationException} に翻訳し、失敗ハンドラが
 * {@code /login?error=<code>} への遷移に変える。
 *
 * <p>{@code code} を持たせているのは、画面に出す文言を URL に載せないため。任意の文字列を
 * クエリパラメータ経由で画面に差し込まれると困るので、対応表はフロント側に置く(設計の§2)。
 */
class GoogleLoginNotAllowedException extends RuntimeException {

	/** Google 側でメールアドレスの所有権が確認されていない(→ docs/adr/0004)。 */
	static final String EMAIL_UNVERIFIED = "email_unverified";

	private final String code;

	GoogleLoginNotAllowedException(String code, String message) {
		super(message);
		this.code = code;
	}

	String getCode() {
		return code;
	}
}
