package com.collabspace.authworkspace.adapter.in.rest.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

public class InvalidInternalTokenException extends AuthenticationException {

	public InvalidInternalTokenException(@Nullable String msg) {
		super(msg);
	}

}
