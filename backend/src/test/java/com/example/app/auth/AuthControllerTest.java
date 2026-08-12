package com.example.app.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.app.config.SecurityConfig;

/**
 * 認証系エンドポイントの入力チェックの検証。
 *
 * <p>Service はモックなので、ここで見るのは「不正な入力が Service に届かないこと」と
 * 「エラーの形(fieldErrors)」だけ。トークンの境界条件は AuthTokenServiceTest、
 * 一連の流れは AuthFlowTest が担当する。
 *
 * <p>@Import(SecurityConfig.class) の理由は PostControllerTest のコメントを参照
 * (これが無いと Boot の既定のセキュリティ設定が使われ、公開エンドポイントが 401 になる)。
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	AuthService authService;

	@MockitoBean
	AuthResponseWriter authResponseWriter;

	@Nested
	@DisplayName("POST /api/auth/signup")
	class Signup {

		@Test
		@DisplayName("ユーザー名が英数字と _ 以外を含むと 400 を返す")
		void rejectsInvalidUsername() throws Exception {
			mockMvc.perform(post("/api/auth/signup").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"username":"日本語ユーザー","displayName":"太郎",
							 "email":"taro@example.com","password":"password123"}
							"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.username").exists());

			// 不正な入力が Service に届いていないこと
			verifyNoInteractions(authService);
		}

		@Test
		@DisplayName("パスワードが 8 文字未満なら 400 を返す")
		void rejectsShortPassword() throws Exception {
			mockMvc.perform(post("/api/auth/signup").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"username":"taro","displayName":"太郎",
							 "email":"taro@example.com","password":"short"}
							"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.password").exists());
		}

		@Test
		@DisplayName("メールアドレスの形式が不正なら 400 を返す")
		void rejectsMalformedEmail() throws Exception {
			mockMvc.perform(post("/api/auth/signup").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"username":"taro","displayName":"太郎",
							 "email":"not-an-email","password":"password123"}
							"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.email").exists());
		}

		@Test
		@DisplayName("入力が正しければ 201 を返す")
		void acceptsValidRequest() throws Exception {
			mockMvc.perform(post("/api/auth/signup").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"username":"taro","displayName":"太郎",
							 "email":"taro@example.com","password":"password123"}
							"""))
					.andExpect(status().isCreated());
		}
	}

	@Nested
	@DisplayName("POST /api/auth/verify-email")
	class VerifyEmail {

		@Test
		@DisplayName("トークンが空なら 400 を返す")
		void rejectsBlankToken() throws Exception {
			mockMvc.perform(post("/api/auth/verify-email").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"token\":\"\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.token").exists());
		}
	}

	@Nested
	@DisplayName("GET /api/auth/me")
	class Me {

		@Test
		@DisplayName("未ログインでも 200 を返す(401 にはしない)")
		void returnsOkForAnonymous() throws Exception {
			// 未ログインは正常な答えとして扱う(→ 設計の決定14)。
			// principal が null で渡ることも、ここで暗黙に検証している。
			mockMvc.perform(get("/api/auth/me"))
					.andExpect(status().isOk());
		}
	}

	@Nested
	@DisplayName("PUT /api/auth/password")
	class ChangePassword {

		@Test
		@DisplayName("未ログインなら認可で弾かれ、Service には届かない")
		void requiresAuthentication() throws Exception {
			mockMvc.perform(put("/api/auth/password").with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword1\"}"));

			// Controller に入っていないこと。
			verifyNoInteractions(authService);
			// 認可で弾かれ、401 を返す役に処理が渡っていること。
			//
			// ステータスコード自体はここでは検証できない。401 を書くのは AuthResponseWriter で、
			// このスライスではそれをモックに差し替えているため実際の応答は 200 のままになる。
			// 本物の 401 とレスポンスの中身は、同じ authenticationEntryPoint を通る
			// AuthFlowTest.rejectsPostWithoutLogin(アプリ全体を起動するので差し替えが無い)で確認している。
			// あちらの対象は POST /api/posts だが、未ログイン時に 401 を書くのは
			// エンドポイントによらず AuthResponseWriter.onUnauthenticated の 1 箇所。
			verify(authResponseWriter).onUnauthenticated(any(), any(), any());
		}
	}
}
