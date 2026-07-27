package com.example.app.category;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 でテストアノテーションのパッケージが技術別モジュールに移動している
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.app.category.dto.CategoryResponse;

/**
 * Controller 層のレスポンス形式の検証。
 * Service はモックにし、Web 層(URL のひも付け・JSON 変換)だけを起動する。
 */
@WebMvcTest(CategoryController.class)
class CategoryControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CategoryService categoryService;

	@Test
	void カテゴリー一覧は200とServiceが返した順序で返す() throws Exception {
		when(categoryService.getCategories()).thenReturn(List.of(
				new CategoryResponse(1L, "お知らせ"),
				new CategoryResponse(2L, "日常")));

		mockMvc.perform(get("/api/categories"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(1))
				.andExpect(jsonPath("$[0].name").value("お知らせ"))
				.andExpect(jsonPath("$[1].name").value("日常"));
	}

	@Test
	void カテゴリーが0件でも200と空配列を返す() throws Exception {
		when(categoryService.getCategories()).thenReturn(List.of());

		mockMvc.perform(get("/api/categories"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
