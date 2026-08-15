package com.example.app.auth;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.example.app.user.User;

/**
 * Google の認証が成立した直後に呼ばれ、「このアプリの誰としてログインするか」を返す役。
 *
 * <p>Spring Security との接続部であり、業務ロジックは持たない。ここが Controller と同じ
 * 位置づけで、判断は {@link GoogleAccountService} に寄せている
 * (→ docs/development/backend-structure-best-practices.md「Controller は薄く」)。
 *
 * <p>この場所を選んでいる理由は<b>認証の途中で走る</b>こと。ログインを断りたいとき
 * ({@code email_verified} が false)に {@code OAuth2AuthenticationException} を投げれば、
 * Spring Security の失敗ハンドラに素直に流れる。成功ハンドラで DB を触る作りだと
 * 「認証が成立してしまった後で拒否する」ことになり後始末が要る。
 */
@Service
public class AppOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	/** Google からクレームを取ってくる標準の実装。差し替えではなく前段として使う。 */
	private final OidcUserService delegate = new OidcUserService();

	private final GoogleAccountService googleAccountService;

	AppOidcUserService(GoogleAccountService googleAccountService) {
		this.googleAccountService = googleAccountService;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
		//ここで実際に Google と HTTP 通信 が起きます。返ってくる oidcUser には、Google が名乗る情報（sub（アカウントの不変 ID）、email、email_verified、name）が詰まっています。
		OidcUser oidcUser = delegate.loadUser(userRequest);

		// getEmailVerified() は Boolean(3 値)。クレームが無ければ null になるので、
		// 「明示的に true」以外はすべて未確認として扱う。
		boolean emailVerified = Boolean.TRUE.equals(oidcUser.getEmailVerified());

		User user;
		try {
			user = googleAccountService.resolve(
					oidcUser.getSubject(),
					oidcUser.getEmail(),
					emailVerified,
					oidcUser.getFullName());
		} catch (GoogleLoginNotAllowedException e) {
			// アプリの言葉で書かれた GoogleLoginNotAllowedException（GoogleLoginNotAllowedException.java:15）は、Spring Security にとっては見知らぬ例外です。
			// そのまま投げると認証フィルタは扱えず、500 エラーになってしまいます。そこで Spring Security の共通語である OAuth2AuthenticationException に包み直します。
			// OAuth2Error のコードはそのまま /login?error=<コード> になる(→ AuthResponseWriter)。
			throw new OAuth2AuthenticationException(new OAuth2Error(e.getCode()), e.getMessage(), e);
		}

		return new AppOidcUser(user, oidcUser.getIdToken(), oidcUser.getUserInfo());
	}
}
