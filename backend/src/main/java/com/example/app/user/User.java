package com.example.app.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String username;

	@Column(name = "display_name", nullable = false, length = 50)
	private String displayName;

	@Column(nullable = false)
	private String email;

	// Google ログインのみのユーザーは NULL
	@Column(name = "password_hash")
	private String passwordHash;

	// Google OIDC の不変 ID(メールではなくこちらをキーにする)
	@Column(name = "google_sub")
	private String googleSub;

	@Column(length = 160)
	private String bio;

	@Column(name = "avatar_image_key")
	private String avatarImageKey;

	// NULL の間はメール未確認(ログイン不可)
	@Column(name = "email_verified_at")
	private LocalDateTime emailVerifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected User() {
		// JPA 用
	}

	public User(String username, String displayName, String email) {
		this.username = username;
		this.displayName = displayName;
		this.email = email;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getEmail() {
		return email;
	}

	public String getBio() {
		return bio;
	}

	public String getAvatarImageKey() {
		return avatarImageKey;
	}

	public LocalDateTime getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	public void setEmailVerifiedAt(LocalDateTime emailVerifiedAt) {
		this.emailVerifiedAt = emailVerifiedAt;
	}
}
