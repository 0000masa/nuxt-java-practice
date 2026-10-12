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

	// パスワードを最後に設定した日時。setPasswordHash が更新する。
	// 認証メールのワーカーが「リセットの依頼より後に変更されたか」を見るのに使う(→ V5 のコメント)
	@Column(name = "password_changed_at")
	private LocalDateTime passwordChangedAt;

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

	// 引数も局所変数もないため createdAt / updatedAt はフィールドに解決される。名前の衝突がないので this. は不要。
	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = createdAt;
	}

	// ここも同様に updatedAt という名前はフィールドしかないため this. は不要。
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

	/** NULL のときはパスワードが未設定(Google ログインのみのユーザーと dev_user)。パスワードログインはできない。 */
	public String getPasswordHash() {
		return passwordHash;
	}

	/**
	 * ハッシュ化済みの値を渡すこと。平文を渡さないよう、呼び出し側で PasswordEncoder を通す。
	 *
	 * <p>{@link #getPasswordChangedAt()} もここで一緒に更新する。会員登録・パスワード変更・リセットの完了の
	 * どの経路でも付け忘れないよう、呼び出し側に任せない。
	 */
	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		this.passwordChangedAt = LocalDateTime.now();
	}

	/** パスワードを最後に設定した日時。V5 より前に設定したユーザーは NULL。 */
	public LocalDateTime getPasswordChangedAt() {
		return passwordChangedAt;
	}

	/** NULL のときは Google 未連携。値が入っていれば Google ログインが使える。 */
	public String getGoogleSub() {
		return googleSub;
	}

	/**
	 * Google アカウントを紐づける(アカウントリンク → CONTEXT.md)。
	 * 渡すのは OIDC の {@code sub} で、メールアドレスではない。
	 */
	public void setGoogleSub(String googleSub) {
		this.googleSub = googleSub;
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
