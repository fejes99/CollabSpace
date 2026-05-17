package com.collabspace.authworkspace.adapter.in.rest;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		// TODO: log the exception with correlationId at ERROR level
		// TODO: return RFC 9457 ProblemDetail: type=about:blank, status=500, detail="An
		// unexpected error occurred."
		return ProblemDetail.forStatus(500);
	}

}
