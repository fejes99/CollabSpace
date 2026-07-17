package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

import java.net.URI;

public abstract class SecurityAuthenticationException extends AuthenticationException {

	protected SecurityAuthenticationException(@Nullable String msg) {
		super(msg);
	}

	public abstract URI getType();

	public abstract String getTitle();

}
