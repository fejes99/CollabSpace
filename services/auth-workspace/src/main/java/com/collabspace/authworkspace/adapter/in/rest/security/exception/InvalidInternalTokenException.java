package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import org.jspecify.annotations.Nullable;

import java.net.URI;

public class InvalidInternalTokenException extends SecurityAuthenticationException {

	public InvalidInternalTokenException(@Nullable String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return errorType("auth/invalid-internal-token");
	}

	@Override
	public String getTitle() {
		return "Invalid internal token";
	}

}
