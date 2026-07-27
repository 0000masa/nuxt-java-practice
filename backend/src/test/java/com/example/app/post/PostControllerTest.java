package com.example.app.post;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
 */
@WebMvcTest(PostController.class)
class PostControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PostService postService;

	@Test
	void 投稿作成は201を返す() throws Exception {
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
	void 本文が空なら400とフィールドエラーを返す() throws Exception {
		mockMvc.perform(post("/api/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"\",\"categoryId\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.body").exists());
	}

	@Test
	void 本文が280文字を超えると400を返す() throws Exception {
		String longBody = "あ".repeat(281);

		mockMvc.perform(post("/api/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"" + longBody + "\",\"categoryId\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.body").exists());
	}

	@Test
	void カテゴリー未指定なら400を返す() throws Exception {
		mockMvc.perform(post("/api/posts")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"こんにちは\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.categoryId").exists());
	}

	@Test
	void タイムラインのlimit上限は50() throws Exception {
		mockMvc.perform(get("/api/posts").param("limit", "51"))
				.andExpect(status().isBadRequest());
	}
}
