package com.example.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 確認メール再送のリクエストボディ。
 *
 * <p>「メールが届かない」「有効期限が切れた」からの復帰経路はこれしかないため、
 * 会員登録と対になる必須の機能になっている(→ docs/adr/0003)。
 */
public record ResendVerificationRequest(
		@NotBlank(message = "メールアドレスを入力してください")
		@Email(message = "メールアドレスの形式が正しくありません")
		String email
) {
}
