package com.collabspace.authworkspace.adapter.in.rest.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

public class TokenRevokedException extends AuthenticationException {

	public TokenRevokedException(@Nullable String msg) {
		super(msg);
	}

}
