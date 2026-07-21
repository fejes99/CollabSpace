package com.collabspace.authworkspace.domain.exception.auth;

import com.collabspace.authworkspace.domain.exception.UnauthorizedException;

import java.net.URI;

public class InvalidCredentialsException extends UnauthorizedException {

	public static final URI TYPE = errorType("auth/invalid-credentials");

	public InvalidCredentialsException() {
		super("Invalid credentials.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
