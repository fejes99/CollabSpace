package com.collabspace.authworkspace.adapter.in.rest.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

public class MalformedIdentityHeadersException extends AuthenticationException {

	public MalformedIdentityHeadersException(@Nullable String msg) {
		super(msg);
	}

}
