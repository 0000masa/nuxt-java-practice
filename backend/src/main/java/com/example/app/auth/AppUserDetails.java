package com.example.app.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.example.app.user.User;

/**
 * ログイン中のユーザーを表す Spring Security 側の入れ物(principal)。
 *
 * <p>Controller で {@code @AuthenticationPrincipal AppUserDetails} と書くとこのオブジェクトが渡る。
 * 未ログインのリクエストでは principal が {@code "anonymousUser"} という文字列になるため、
 * 型を指定した引数には {@code null} が入る(公開エンドポイントではこの null を前提にする)。
 * そうなるのは AnonymousAuthenticationFilter が未ログイン時に匿名の principal を入れるため
 * → docs/notes/java/spring/security-filter-chain.md
 *
 * <p><b>保持する値を最小限にしている理由</b>: このオブジェクトはシリアライズされて
 * SPRING_SESSION_ATTRIBUTES に保存される。表示名や bio まで持たせると、プロフィール編集後も
 * セッションの中身が古い値のまま残ってしまう。あとから変わる値は都度 DB から読む。
 */
public class AppUserDetails implements UserDetails, CredentialsContainer {

	private final Long userId;
	private final String email;

	// final にしないのは eraseCredentials() で消せるようにするため(下のコメント参照)。
	private String passwordHash;

	private final boolean emailVerified;

	public AppUserDetails(Long userId, String email, String passwordHash, boolean emailVerified) {
		this.userId = userId;
		this.email = email;
		this.passwordHash = passwordHash;
		this.emailVerified = emailVerified;
	}

	public static AppUserDetails from(User user) {
		// != null で比較した結果は true / false の boolean です。「日時が入っている = 確認済み」という DB の表現を、ここで boolean に翻訳している
		return new AppUserDetails(user.getId(), user.getEmail(), user.getPasswordHash(),
				user.getEmailVerifiedAt() != null);
	}

	public Long getUserId() {
		return userId;
	}

	/**
	 * ロールは持たせない。このアプリに権限の区別が無いため(管理者ロールは設計概要 §8 のとおり v2 候補)。
	 * 空でも認証済みかどうかの判定には影響しない。
	 */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	/** Spring Security の言う username = ログインの識別子。このアプリではメールアドレス(users.username ではない)。 */
	@Override
	public String getUsername() {
		return email;
	}

	/**
	 * メール確認が終わっていないユーザーはログインさせない。
	 * false を返すと DaoAuthenticationProvider が DisabledException を投げる。
	 */
	@Override
	public boolean isEnabled() {
		return emailVerified;
	}

	/**
	 * 認証が成功した直後に Spring Security(ProviderManager)が呼ぶ。
	 *
	 * <p>これを実装しないと、パスワードのハッシュが principal ごとシリアライズされて
	 * SPRING_SESSION_ATTRIBUTES に残り続ける。認証後のハッシュは不要なので消しておく。
	 */
	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}
}
