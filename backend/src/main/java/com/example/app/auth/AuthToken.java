package com.example.app.auth;

import java.time.LocalDateTime;

import com.example.app.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * メール確認 / パスワードリセットに使う使い捨てトークン(auth_tokens テーブルの 1 行)。
 *
 * <p><b>token カラムに入るのは生の値ではなく SHA-256 ハッシュ。</b>生の値はメールの URL にだけ載る。
 * DB が漏れてもトークンを復元できないようにするため(設計の決定6)。
 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	// 生の値の SHA-256 を 16 進で表した 64 文字。UNIQUE index が張られているので 1 行を直接引ける。
	@Column(nullable = false)
	private String token;

	// EnumType.STRING … enum の「名前」を保存する。既定の ORDINAL(宣言順の数値)にすると
	// 後から enum の並び順を変えただけで既存データの意味が変わってしまうため、必ず STRING を指定する。
	//EMAIL_VERIFICATION.name() → "EMAIL_VERIFICATION" を取り出す
	//ここで重要なのが「既定値が ORDINAL である」という点です。つまり @Enumerated を書き忘れると、意図せず数値保存になります。
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AuthTokenPurpose purpose;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	// NULL の間は未使用。使ったら日時を入れて二度目を弾く。
	@Column(name = "used_at")
	private LocalDateTime usedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected AuthToken() {
		// JPA 用
	}

	AuthToken(User user, String hashedToken, AuthTokenPurpose purpose, LocalDateTime expiresAt) {
		this.user = user;
		this.token = hashedToken;
		this.purpose = purpose;
		this.expiresAt = expiresAt;
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public User getUser() {
		return user;
	}

	public AuthTokenPurpose getPurpose() {
		return purpose;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public LocalDateTime getUsedAt() {
		return usedAt;
	}

	void markUsed(LocalDateTime usedAt) {
		this.usedAt = usedAt;
	}
}
