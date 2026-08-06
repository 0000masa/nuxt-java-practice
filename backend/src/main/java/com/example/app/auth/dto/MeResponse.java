package com.example.app.auth.dto;

import com.example.app.user.User;

/**
 * {@code GET /api/auth/me} のレスポンス。
 *
 * <p>ログインしていないときは {@code {"user": null}} を 200 で返す(401 にはしない)。
 * 未ログインはエラーではなく正常な答えであり、こうしておくとフロントの 401 共通処理に
 * 例外を作らずに済む。設計 → 2026-08-05-phase3-auth-design.md の決定14
 */
//Java はクラスやレコードの中身については宣言の順番を気にしません。
// コンパイラは先にクラス全体の中身を把握してから、各行の型を解決します。順番を気にするのは「メソッドの中のローカル変数」だけです。
public record MeResponse(CurrentUser user) {

	public record CurrentUser(Long id, String username, String displayName, String email) {
	}

	public static MeResponse of(User user) {
		return new MeResponse(
				new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail()));
	}

	public static MeResponse anonymous() {
		//record は、何も書かなければ null を拒否しません。
		return new MeResponse(null);
	}
}
