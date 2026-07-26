package com.example.app.common.exception;

/** 認可されていない操作(HTTP 403 に変換される) */
public class ForbiddenOperationException extends RuntimeException {

	public ForbiddenOperationException(String message) {
		super(message);
	}
}
