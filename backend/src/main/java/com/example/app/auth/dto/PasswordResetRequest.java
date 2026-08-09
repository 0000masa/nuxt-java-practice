package com.example.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * パスワードリセットの申請(メールを送ってもらう側)のリクエストボディ。
 *
 * <p>ログインしていない状態で行う操作。ログイン中に変える方は
 * {@link ChangePasswordRequest}(パスワード変更)で、別の操作として扱う。
 */
public record PasswordResetRequest(
		@NotBlank(message = "メールアドレスを入力してください")
		@Email(message = "メールアドレスの形式が正しくありません")
		String email
) {
}
