package com.example.app.post;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.app.post.dto.PostResponse;

/**
 * Controller 層のバリデーションとレスポンス形式の検証。
 * Service はモックにし、Web 層(リクエスト変換・バリデーション・例外ハンドリング)だけを起動する。
 * エンドポイントが複数あるので @Nested で分ける。
 *
 * <p>WebMvcTest / MockitoBean / MockMvc といった共通の仕組みの解説は
 * CategoryControllerTest のコメントにまとめてある(ここでは重複させない)。
 */
@WebMvcTest(PostController.class)
class PostControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PostService postService;

	@Nested
	@DisplayName("POST /api/posts")
	class CreatePost {

		@Test
		@DisplayName("投稿作成は 201 を返す")
		void returnsCreatedForValidRequest() throws Exception {
			PostResponse response = new PostResponse(1L, "こんにちは", LocalDateTime.now(),
					new PostResponse.UserSummary(1L, "dev_user", "開発ユーザー"),
					new PostResponse.CategorySummary(1L, "雑談"));
			when(postService.create(any())).thenReturn(response);

			mockMvc.perform(post("/api/posts")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"こんにちは\",\"categoryId\":1}"))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.id").value(1))
					.andExpect(jsonPath("$.user.username").value("dev_user"));
		}

		@Test
		@DisplayName("本文が空なら 400 とフィールドエラーを返す")
		void returnsBadRequestWhenBodyIsBlank() throws Exception {
			mockMvc.perform(post("/api/posts")
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
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"" + longBody + "\",\"categoryId\":1}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.body").exists());
		}

		@Test
		@DisplayName("カテゴリー未指定なら 400 を返す")
		void returnsBadRequestWhenCategoryIdIsMissing() throws Exception {
			mockMvc.perform(post("/api/posts")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\":\"こんにちは\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors.categoryId").exists());
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
