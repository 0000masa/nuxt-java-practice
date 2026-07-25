package com.example.app.category;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.app.category.dto.CategoryResponse;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

	// 処理は自分で行わず CategoryService に任せる(Controller は薄く保つ)。
	private final CategoryService categoryService;

	public CategoryController(CategoryService categoryService) {
		this.categoryService = categoryService;
	}

	@GetMapping
	public List<CategoryResponse> list() {
		return categoryService.getCategories();
	}
}
