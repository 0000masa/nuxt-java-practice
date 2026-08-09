package com.example.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * パスワードリセットの実行(新しいパスワードを設定する側)のリクエストボディ。
 *
 * @param token       メールのリンクに載っていた生の値。フロントの /password-reset/confirm ページが
 *                    クエリパラメータから取り出して送る
 * @param newPassword 新しいパスワード(平文)。上限 72 は BCrypt の仕様に合わせたもの
 */
public record PasswordResetConfirmRequest(
		@NotBlank(message = "トークンがありません")
		String token,

		@NotBlank(message = "新しいパスワードを入力してください")
		@Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
		String newPassword
) {
}
