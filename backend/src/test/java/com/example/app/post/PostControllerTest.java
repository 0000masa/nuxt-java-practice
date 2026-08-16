package com.example.app.post;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 でテストアノテーションのパッケージが技術別モジュールに移動している
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.app.auth.AppUserDetails;
import com.example.app.auth.AppOidcUserService;
import com.example.app.auth.AuthResponseWriter;
import com.example.app.common.exception.ForbiddenOperationException;
import com.example.app.config.SecurityConfig;
import com.example.app.post.dto.CreatePostRequest;
import com.example.app.post.dto.PostResponse;

/**
 * Controller 層のバリデーションとレスポンス形式の検証。
 * Service はモックにし、Web 層(リクエスト変換・バリデーション・例外ハンドリング)だけを起動する。
 * エンドポイントが複数あるので @Nested で分ける。
 *
 * <p>WebMvcTest / MockitoBean / MockMvc といった共通の仕組みの解説は
 * CategoryControllerTest のコメントにまとめてある(ここでは重複させない)。
 *
 * <p><b>認証について</b>: フェーズ3 で投稿の作成・削除が認証必須になったため、書き込み系のリクエストには
 * {@code with(user(...))}(ログイン済みに見せる)と {@code with(csrf())}(CSRF トークンを付ける)を
 * 添えている。これを忘れると本題のバリデーションに到達する前に 401 / 403 になる。
 *
 * <p>@Import(SecurityConfig.class) でアプリ本体の認可ルールを読み込んでいる。これが無いと
 * @WebMvcTest は Spring Boot の「既定のセキュリティ設定」(全リクエスト認証必須)を使ってしまい、
 * 公開しているはずの GET /api/posts が 401 になる。読み込むことで「閲覧は公開・書き込みは認証必須」
 * という設計そのものをテストで守れる。
 */
@WebMvcTest(PostController.class)
@Import(SecurityConfig.class)
class PostControllerTest {

	// SecurityConfig が必要とする部品。認証エラー時の JSON 出力担当で、ここではモックで足りる。
	@MockitoBean
	AuthResponseWriter authResponseWriter;

	// @MockitoBean AppOidcUserService … SecurityConfig の oauth2Login() が要求する部品(フェーズ4)。
	//   Google ログインの経路はこのテストを通らないのでモックで足りる。
	@MockitoBean
	AppOidcUserService appOidcUserService;

	// ログイン済みのユーザーとして振る舞わせる principal。id だけが Service に渡る。
	private static final AppUserDetails LOGGED_IN_USER =
			new AppUserDetails(7L, "taro@example.com", null, true);

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PostService postService;

	@Nested
	@DisplayName("POST /api/posts")
	class CreatePost {

		@Test
		@DisplayName("投稿作成は 201 を返し、ログイン中のユーザーの id を Service に渡す")
		void returnsCreatedForValidRequest() throws Exception {
			PostResponse response = new PostResponse(1L, "こんにちは", LocalDateTime.now(),
					new PostResponse.UserSummary(7L, "taro", "太郎"),
					new PostResponse.CategorySummary(1L, "雑談"));
			when(postService.create(any(), any())).thenReturn(response);

			mockMvc.perform(post("/api/posts")
					.with(user(LOGGED_IN_USER)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"こんにちは\",\"categoryId\":1}"))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.id").value(1))
					.andExpect(jsonPath("$.user.username").value("taro"));

			// 送った JSON が CreatePostRequest に詰め替わっていること、および principal から取り出した id が
			// 投稿者として渡っていること。投稿者はフロントの言い値ではなくセッションから決まる、
			// という設計をここで固定している。
			// レスポンスの検証(上の jsonPath)はモックに返させた値を見ているだけなので、
			// リクエストの中身が正しく届いたかはこの verify でしか確かめられない。
			// CreatePostRequest は record で equals が自動生成されるため、値の一致で比較できる。
			verify(postService).create(eq(new CreatePostRequest("こんにちは", 1L)), eq(7L));
		}

		@Test
		@DisplayName("本文が空なら 400 とフィールドエラーを返す")
		void returnsBadRequestWhenBodyIsBlank() throws Exception {
			mockMvc.perform(post("/api/posts")
					.with(user(LOGGED_IN_USER)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"\",\"categoryId\":1}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.body").exists());
		}

		@Test
		@DisplayName("本文が 280 文字を超えると 400 を返す")
		void returnsBadRequestWhenBodyExceedsMaxLength() throws Exception {
			String longBody = "あ".repeat(281);

			mockMvc.perform(post("/api/posts")
					.with(user(LOGGED_IN_USER)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"" + longBody + "\",\"categoryId\":1}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.body").exists());
		}

		@Test
		@DisplayName("カテゴリー未指定なら 400 を返す")
		void returnsBadRequestWhenCategoryIdIsMissing() throws Exception {
			mockMvc.perform(post("/api/posts")
					.with(user(LOGGED_IN_USER)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"こんにちは\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.categoryId").exists());
		}
	}

	@Nested
	@DisplayName("DELETE /api/posts/{id}")
	class DeletePost {

		@Test
		@DisplayName("他人の投稿を削除しようとすると 403 を返す")
		void returnsForbiddenWhenNotOwner() throws Exception {
			// Service が投げた例外が GlobalExceptionHandler で 403 + ErrorResponse に変換されることの検証。
			// 「誰の投稿か」の判定そのものは PostService の責務なのでここでは踏み込まない。
			// delete は戻り値が void なので when(...).thenReturn(...) が使えない。
			// void メソッドに台本を渡すときは doThrow(...).when(モック) と順序が逆になる。
			doThrow(new ForbiddenOperationException("自分の投稿以外は削除できません"))
					.when(postService).delete(eq(1L), eq(7L));

			mockMvc.perform(delete("/api/posts/1")
					.with(user(LOGGED_IN_USER)).with(csrf()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value("自分の投稿以外は削除できません"));
		}
	}

	@Nested
	@DisplayName("GET /api/posts")
	class GetTimeline {

		@Test
		@DisplayName("limit の上限は 50")
		void returnsBadRequestWhenLimitExceedsMax() throws Exception {
			mockMvc.perform(get("/api/posts").param("limit", "51"))
					.andExpect(status().isBadRequest());
		}
	}
}
