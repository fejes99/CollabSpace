package com.collabspace.authworkspace.application.util;

import com.collabspace.authworkspace.domain.exception.auth.InvalidTokenException;

import java.nio.charset.StandardCharsets;

public final class RefreshTokenValidator {

	private static final int MAX_TOKEN_BYTES = 256;

	private RefreshTokenValidator() {
	}

	public static void validate(String token) {
		if (token == null || token.isBlank()) {
			throw new InvalidTokenException();
		}
		if (token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) {
			throw new InvalidTokenException();
		}
	}

}
