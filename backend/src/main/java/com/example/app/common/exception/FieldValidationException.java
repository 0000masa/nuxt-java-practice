package com.example.app.common.exception;

/**
 * 特定の入力項目に紐づく業務ルール違反 → HTTP 400 + fieldErrors。
 *
 * <p>@Valid の形式チェックを通ったあと、DB を見て初めて分かる項目単位の不備に使う。
 * 例: そのメールアドレスは既に登録されている / そのメールアドレスは登録されていない /
 * そのユーザー名は既に使われている。
 *
 * <p>項目名を持つのでレスポンスの {@code fieldErrors} に載せられ、フォームの該当入力欄に
 * そのまま表示できる。@Valid の違反と同じ形のレスポンスになるため、フロントは
 * 「どちらで弾かれたか」を気にせず fieldErrors を見るだけでよい。
 *
 * <p>項目に紐づかないものは {@link InvalidRequestException} を使う。
 *
 * <p>なお「そのメールアドレスは登録済みか」を利用者に伝えることはユーザー列挙を許すことになるが、
 * これは学習用アプリとして意図的に選んだ判断
 * → docs/adr/0003-account-enumeration-and-unverified-signup.md
 */
public class FieldValidationException extends RuntimeException {

	private final String field;

	public FieldValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
