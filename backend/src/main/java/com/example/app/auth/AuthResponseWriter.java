package com.example.app.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import com.example.app.common.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
// Spring Boot 4 が使う Jackson は 3 系で、パッケージが com.fasterxml.jackson.databind から
// tools.jackson.databind に変わっている(アノテーションだけは com.fasterxml.jackson.annotation のまま)。
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security がリダイレクトや空レスポンスを返す場面を、JSON レスポンスに差し替える役。
 *
 * <p>formLogin / logout の既定の動きは HTML アプリ向け(ログインページへのリダイレクト)なので、
 * そのままでは Nuxt から使えない。各メソッドは Spring Security の
 * AuthenticationSuccessHandler / AuthenticationFailureHandler / LogoutSuccessHandler /
 * AuthenticationEntryPoint / AccessDeniedHandler の形に合わせてあり、
 * {@code config/SecurityConfig} からメソッド参照で渡している。
 *
 * <p>返す JSON の形はアプリ全体で 1 つ({@link ErrorResponse})に揃える。
 * 例外 → HTTP の変換を集約するという GlobalExceptionHandler と同じ考え方だが、
 * こちらは Controller に到達する前のフィルタ段階で起きる事象を扱うため別の場所になる。
 */
@Component
public class AuthResponseWriter {

	private final ObjectMapper objectMapper;
	private final AuthService authService;

	public AuthResponseWriter(ObjectMapper objectMapper, AuthService authService) {
		this.objectMapper = objectMapper;
		this.authService = authService;
	}

	/** ログイン成功 → 200 + 現在ユーザー。フロントはこのレスポンスをそのままストアに入れられる。 */
	public void onLoginSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
		writeJson(response, HttpStatus.OK, authService.getCurrentUser(principal.getUserId()));
	}

	/**
	 * ログイン失敗 → 401 + エラーメッセージ。
	 *
	 * <p>メール未確認({@link DisabledException})だけはメッセージを区別する。ここを隠すと
	 * 利用者は「パスワードが違う」と誤解したまま復帰できない。設計 → 2026-08-05-phase3-auth-design.md
	 *
	 * <p>なお DisabledException はパスワードの照合より前に投げられる(AbstractUserDetails
	 * AuthenticationProvider が先に有効性を検査する)ため、パスワードが間違っていても
	 * このメッセージになる。メールアドレスの登録有無が分かる形だが、これは決定8 で許容している。
	 */
	public void onLoginFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String message = exception instanceof DisabledException
				? "メールアドレスの確認が完了していません。確認メールを再送してください"
				: "メールアドレスまたはパスワードが違います";
		writeJson(response, HttpStatus.UNAUTHORIZED, ErrorResponse.of(message));
	}

	/** ログアウト成功 → 204(返す中身なし)。既定はログインページへのリダイレクト。 */
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		response.setStatus(HttpStatus.NO_CONTENT.value());
	}

	/**
	 * 認証が必要なエンドポイントに未ログインで来た → 401。
	 * 既定はログインページへのリダイレクトなので、JSON API 向けに差し替える。
	 */
	public void onUnauthenticated(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		writeJson(response, HttpStatus.UNAUTHORIZED, ErrorResponse.of("ログインが必要です"));
	}

	/**
	 * ログイン済みだが許可されていない → 403。
	 * このアプリで実際に通るのは主に <b>CSRF トークンが無い / 合わない</b>ケース。
	 */
	public void onAccessDenied(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		writeJson(response, HttpStatus.FORBIDDEN, ErrorResponse.of("この操作は許可されていません"));
	}

	private void writeJson(HttpServletResponse response, HttpStatus status, Object body) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), body);
	}
}
