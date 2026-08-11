package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.example.app.auth.AuthResponseWriter;

/**
 * Spring Security の設定。
 *
 * <p>設計 → docs/superpowers/specs/2026-08-05-phase3-auth-design.md
 * <p>方式の選定理由 → docs/adr/0002-session-cookie-over-jwt.md
 * <p>ここの設定がフィルタの列にどう化けるか、関係ファイルはどれか
 * → docs/notes/java/spring/security-filter-chain.md
 *
 * <p>フェーズ4 で Google ログインを足すときは、ここに {@code oauth2Login()} が加わる。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, AuthResponseWriter authResponseWriter) throws Exception {
		http
				// 認可: 閲覧は公開、書き込みは認証必須(設計の決定1)。
				//
				// ルールは上から順に評価され、最初に一致したものが適用される(下に書いたものは効かない)。
				// そのため「公開するものを列挙 → 残りの /api/** は認証必須」という順序にしてある。
				// 逆順にすると全部公開になってしまう。
				.authorizeHttpRequests(auth -> auth
						// 未ログインでも見られるもの。
						.requestMatchers(HttpMethod.GET,
								"/api/posts", "/api/posts/*", "/api/categories", "/api/auth/me")
						.permitAll()
						// 未ログインで叩けないと登録もログインもできないもの。
						// ログイン(/api/auth/login)とログアウト(/api/auth/logout)は認可より手前の
						// フィルタが処理するのでこの列挙は本来不要だが、一覧として読めるように並べている。
						.requestMatchers(HttpMethod.POST,
								"/api/auth/signup", "/api/auth/verify-email", "/api/auth/verification/resend",
								"/api/auth/password-reset/request", "/api/auth/password-reset/confirm",
								"/api/auth/login", "/api/auth/logout")
						.permitAll()
						// 上で公開扱いにしなかった API は認証必須。ここが既定拒否になっているので、
						// 新しいエンドポイントを足したときに「うっかり公開」にはならない。
						// 現在これに該当するのは POST /api/posts、DELETE /api/posts/{id}、PUT /api/auth/password。
						.requestMatchers("/api/**").authenticated()
						// API 以外(フェーズ11 で static/ に置く Nuxt の SSG 出力)は公開。
						.anyRequest().permitAll())

				// CSRF: XSRF-TOKEN Cookie に期待値を書き、フロントは X-XSRF-TOKEN ヘッダで送り返す。
				//
				// spa() は Spring Security 7 で入った SPA 向けのまとめ設定で、素で組むと必要になる
				// 面倒を 3 つ引き受けてくれる:
				//   1. CookieCsrfTokenRepository(JavaScript から読めるよう HttpOnly を外したもの)の設定
				//   2. BREACH 対策の既定ハンドラは Cookie の値をそのまま送り返しても通らない形に
				//      エンコードするため、SPA 向けの解決方法に差し替える
				//   3. 認証成功時とログアウト成功時に古いトークンが破棄されるので、新しい Cookie を出し直す
				// 素の REST API で CSRF が不要になるのは Bearer トークン方式のときだけで、
				// Cookie にセッション ID を載せるこの構成では必要(→ ADR-0002)。
				.csrf(csrf -> csrf.spa())

				// ログイン: Spring Security 標準の formLogin に乗せる(→ ADR-0002)。
				// 対応する Controller メソッドは存在せず、フィルタが直接処理する。
				// リクエストボディが JSON ではなく form-urlencoded になるのはこの方式の代償。
				.formLogin(form -> form
						.loginProcessingUrl("/api/auth/login")
						.usernameParameter("email") // 既定は username。ログインの識別子はメールアドレスなので変更する
						.passwordParameter("password")
						.successHandler(authResponseWriter::onLoginSuccess)
						.failureHandler(authResponseWriter::onLoginFailure))

				// ログアウト: セッションを破棄し、SESSION Cookie と CSRF Cookie を消す。
				// CSRF が有効なので POST のみ受け付ける。
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler(authResponseWriter::onLogoutSuccess))

				// 既定ではログインページへリダイレクトしてしまうので、JSON の 401 / 403 に差し替える。
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(authResponseWriter::onUnauthenticated)
						.accessDeniedHandler(authResponseWriter::onAccessDenied));

		return http.build();
	}

	/**
	 * パスワードのハッシュ化に使うエンコーダ。
	 *
	 * <p>{@code new BCryptPasswordEncoder()} ではなく委譲版を使う。委譲版はハッシュの先頭に
	 * {@code {bcrypt}} というアルゴリズム名を付けて保存するため、将来別のアルゴリズムへ移行するときに
	 * 「この行はどのアルゴリズムで作られたか」が値そのものから分かり、新旧を混在させたまま切り替えられる。
	 * 既定のアルゴリズムは BCrypt なので、保存される値は {@code {bcrypt}$2a$10$...} の形になる。
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
