package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.app.user.User;

/**
 * Google ログインの principal の検証。
 *
 * <p>見ているのは実質 1 点、{@code getName()} がメールアドレスを返すこと。ここが OIDC の既定
 * (= {@code sub})のままでも<b>画面上は何も壊れない</b>。露見するのは
 * 「パスワードをリセットしたのに Google ログインのセッションだけ生き残る」という形だけで、
 * 手で気づくのがほぼ不可能なため、テストで押さえておく(設計の決定8)。
 */
class AppOidcUserTest {

	@Test
	@DisplayName("principal は sub ではなくメールアドレスを名前に使い、パスワードを持たない")
	void buildsPrincipalFromUser() {
		User user = new User("google_taro", "太郎", "taro@example.com");
		user.setEmailVerifiedAt(LocalDateTime.now());
		// id は DB が採番するので、テストでは直接入れる。
		ReflectionTestUtils.setField(user, "id", 42L);

		Instant now = Instant.now();
		// クレームの email はあえて users.email と違う値にしてある。getName() がクレームではなく
		// users.email を返していることを区別するため(同じ値だと実装が壊れても通ってしまう)。
		OidcIdToken idToken = new OidcIdToken("id-token-value", now, now.plus(1, ChronoUnit.HOURS),
				Map.of("sub", "google-sub-123", "email", "google-side@example.com"));

		AppOidcUser principal = new AppOidcUser(user, idToken, null);

		// SPRING_SESSION.PRINCIPAL_NAME に入るのがこの値。UserSessionManager はここを鍵に
		// そのユーザーのセッションを引くので、パスワードログイン側と揃っていなければならない。
		assertThat(principal.getName()).isEqualTo("taro@example.com");
		assertThat(principal.getUserId()).isEqualTo(42L);
		// パスワードハッシュはセッションに載せない。
		assertThat(principal.getPassword()).isNull();
		assertThat(principal.isEnabled()).isTrue();
	}
}
