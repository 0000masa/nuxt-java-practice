package com.example.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 会員登録のリクエストボディ。
 *
 * <p>ここで守るのは「形が正しいか」まで。メールアドレスや username が既に使われているかは
 * DB を見ないと分からないので AuthService の責務(400 + fieldErrors で返る)。
 *
 * @param username    表示・検索用の一意な名前(@xxx 相当)。ログインの識別子ではない
 * @param displayName 自由に付けられる名前。日本語可、一意でなくてよい
 * @param email       ログインの識別子
 * @param password    平文。保存前に BCrypt でハッシュ化する
 */
public record SignupRequest(
		// 英数字と _ のみ。検索ラボでユーザー名の前方一致・部分一致を試す題材になるので、
		// 記号や日本語を許さず素直な文字種にしている。
		@NotBlank(message = "ユーザー名を入力してください")
		@Pattern(regexp = "^[A-Za-z0-9_]{3,30}$", message = "ユーザー名は英数字と_のみ、3〜30文字で入力してください")
		String username,

		@NotBlank(message = "表示名を入力してください")
		@Size(max = 50, message = "表示名は50文字以内で入力してください")
		String displayName,

		@NotBlank(message = "メールアドレスを入力してください")
		@Email(message = "メールアドレスの形式が正しくありません")
		@Size(max = 255, message = "メールアドレスは255文字以内で入力してください")
		String email,

		// 上限 72 は BCrypt の仕様に合わせたもの。BCrypt は 72 バイトを超えた分を無視するので、
		// それより長いパスワードを受け付けると「後半を書き間違えても通る」状態になる。
		@NotBlank(message = "パスワードを入力してください")
		@Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
		String password
) {
}
