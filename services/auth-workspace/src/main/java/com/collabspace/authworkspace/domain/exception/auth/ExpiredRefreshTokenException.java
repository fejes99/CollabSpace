package com.collabspace.authworkspace.domain.exception.auth;

import com.collabspace.authworkspace.domain.exception.UnauthorizedException;

import java.net.URI;

public class ExpiredRefreshTokenException extends UnauthorizedException {

	public static final URI TYPE = errorType("auth/refresh-token-expired");

	public ExpiredRefreshTokenException() {
		super("Expired token");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
