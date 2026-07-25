package com.example.app.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.app.common.dto.ErrorResponse;

import jakarta.validation.ConstraintViolationException;

/**
 * 例外を HTTP レスポンスへ変換する共通ハンドラ。
 * 各 Controller に try-catch を書かず、ここに集約する。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound(ResourceNotFoundException e) {
		return ErrorResponse.of(e.getMessage());
	}

	@ExceptionHandler(ForbiddenOperationException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleForbidden(ForbiddenOperationException e) {
		return ErrorResponse.of(e.getMessage());
	}

	/** リクエストボディ(@Valid)のバリデーションエラー */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return new ErrorResponse("入力内容に誤りがあります", fieldErrors);
	}

	/** クエリパラメータ(@Min / @Max など)のバリデーションエラー */
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleConstraintViolation(ConstraintViolationException e) {
		return ErrorResponse.of("リクエストパラメータが不正です: " + e.getMessage());
	}
}
