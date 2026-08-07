package com.example.app.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * メール確認のリクエストボディ。
 *
 * <p>token はメールのリンクに載っていた生の値。フロントの /verify-email ページが
 * クエリパラメータから取り出してこの形で送る。
 *
 * <p>GET ではなく POST なのは、メールソフトのリンク先読みでトークンが勝手に消費される事故を
 * 避けるため(設計の決定5)。
 */
public record VerifyEmailRequest(
		@NotBlank(message = "トークンがありません")
		String token
) {
}
