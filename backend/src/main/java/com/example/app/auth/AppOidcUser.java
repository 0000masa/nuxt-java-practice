package com.example.app.auth;

import java.util.Map;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.example.app.user.User;

/**
 * Google ログイン中のユーザーを表す principal。
 *
 * <p><b>なぜ 2 つの型を兼ねるのか</b>: 素の {@code oauth2Login()} は principal に
 * {@code OidcUser} を入れる。ところがこのアプリの Controller は
 * {@code @AuthenticationPrincipal AppUserDetails} で受け取っており、型が合わないと
 * <b>黙って null が渡る</b>。認可自体は通っているので 401 にすらならず、
 * Controller の中で NullPointerException になって 500 で落ちる。
 * 両方を満たす型にすることで、既存の Controller に一切手を入れずに済む(設計の決定7)。
 *
 * <p>このオブジェクトは SPRING_SESSION_ATTRIBUTES にシリアライズされて保存される。
 * {@link AppUserDetails} が値を最小限にしているのに対し、こちらは ID トークンとクレームを
 * 抱えるぶん行が大きくなる。<b>受容している</b>: ID トークンは発行後に変わらない値なので、
 * {@code AppUserDetails} が避けている「セッション内の値が古くなる」問題は起きない。
 */
public class AppOidcUser extends AppUserDetails implements OidcUser {

	private final OidcIdToken idToken;
	private final OidcUserInfo userInfo;

	public AppOidcUser(User user, OidcIdToken idToken, OidcUserInfo userInfo) {
		// パスワードハッシュは渡さない。Google ログインの経路では照合に使わないうえ、
		// セッションに残す理由が無い(AppUserDetails.eraseCredentials と同じ考え方)。
		//
		// メール確認済みかは users.email_verified_at から判定する。この経路を通る User は
		// GoogleAccountService が必ず確認済みにしているので実質 true だが、true と直接書くと
		// 同じ事実がこことエンティティの 2 か所に散る(AppUserDetails.from と同じ変換式にする)。
		super(user.getId(), user.getEmail(), null, user.getEmailVerifiedAt() != null);
		this.idToken = idToken;
		this.userInfo = userInfo;
	}

	/**
	 * SPRING_SESSION.PRINCIPAL_NAME に入る値を決める、重要なメソッド。
	 *
	 * <p>OIDC の既定はこれが {@code sub} を返す。そのままにすると
	 * {@link UserSessionManager} がメールアドレスで引いても Google 由来のセッションが
	 * 見つからず、<b>パスワードをリセットしても Google ログインのセッションだけ生き残る</b>。
	 * パスワードログインと同じくメールアドレスを返して揃える(設計の決定8)。
	 */
	@Override
	public String getName() {
		return getUsername();
	}

	@Override
	public OidcIdToken getIdToken() {
		return idToken;
	}

	@Override
	public OidcUserInfo getUserInfo() {
		return userInfo;
	}

	@Override
	public Map<String, Object> getClaims() {
		return idToken.getClaims();
	}

	/** OIDC では属性 = ID トークンのクレーム。{@link #getClaims()} と同じものを返す。 */
	@Override
	public Map<String, Object> getAttributes() {
		return getClaims();
	}
}
