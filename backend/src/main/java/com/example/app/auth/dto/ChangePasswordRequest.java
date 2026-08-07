package com.example.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ログイン中のパスワード変更のリクエストボディ。
 *
 * <p>現在のパスワードを要求するのは、セッションを盗まれた相手にパスワードごと
 * 奪われないようにするため(セッションだけでは変更できない)。
 *
 * @param currentPassword 現在のパスワード(平文)。照合に使うだけで保存しない
 * @param newPassword     新しいパスワード(平文)
 */
public record ChangePasswordRequest(
		@NotBlank(message = "現在のパスワードを入力してください")
		String currentPassword,

		@NotBlank(message = "新しいパスワードを入力してください")
		@Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
		String newPassword
) {
}
