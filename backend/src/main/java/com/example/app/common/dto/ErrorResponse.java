package com.example.app.common.dto;

import java.util.Map;

/**
 * エラーレスポンスの共通形。
 *
 * @param message     人間向けのエラーメッセージ
 * @param fieldErrors バリデーションエラー時のみ: フィールド名 → メッセージ(それ以外は null)
 */
public record ErrorResponse(String message, Map<String, String> fieldErrors) {

	public static ErrorResponse of(String message) {
		return new ErrorResponse(message, null);
	}
}
