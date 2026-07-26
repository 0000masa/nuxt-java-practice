package com.example.app.common.exception;

/** 対象リソースが存在しない(HTTP 404 に変換される) */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
