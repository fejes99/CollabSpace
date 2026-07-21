package com.collabspace.authworkspace.domain.exception.auth;

import com.collabspace.authworkspace.domain.exception.UnauthorizedException;

import java.net.URI;

public class InvalidTokenException extends UnauthorizedException {

	public static final URI TYPE = errorType("auth/refresh-token-invalid");

	public InvalidTokenException() {
		super("Invalid token");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
