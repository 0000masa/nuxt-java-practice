package com.example.app.category;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.app.category.dto.CategoryResponse;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

	private final CategoryRepository categoryRepository;

	public CategoryController(CategoryRepository categoryRepository) {
		this.categoryRepository = categoryRepository;
	}

	@GetMapping
	public List<CategoryResponse> list() {
		return categoryRepository.findAllByOrderByDisplayOrderAsc().stream()
				.map(CategoryResponse::from)
				.toList();
	}
}
