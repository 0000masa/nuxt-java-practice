package com.example.app.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 運営管理のマスタデータ。レコードは Flyway(V2__insert_categories.sql)で投入し、
 * アプリからは参照のみ。
 */
@Entity
@Table(name = "categories")
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	protected Category() {
		// JPA 用
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Integer getDisplayOrder() {
		return displayOrder;
	}
}
