package com.example.app.category.dto;

import com.example.app.category.Category;

public record CategoryResponse(Long id, String name) {

	public static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getName());
	}
}
