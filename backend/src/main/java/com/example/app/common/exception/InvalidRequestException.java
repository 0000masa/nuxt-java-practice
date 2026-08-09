package com.example.app.common.exception;

/**
 * リクエストの内容が業務ルール上受け付けられない → HTTP 400。
 *
 * <p>@Valid による形式チェック(必須・文字数・形式)を通ったあと、DB を見て初めて分かる不備に使う。
 * 例: トークンの有効期限が切れている、未確認のアカウントでパスワードリセットを申請した。
 *
 * <p>項目名を添えてフォームの入力欄にエラーを出したい場合は {@link DuplicateValueException} を使う。
 */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}
}
