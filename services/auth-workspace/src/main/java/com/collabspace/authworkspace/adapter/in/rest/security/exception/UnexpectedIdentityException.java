package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public class UnexpectedIdentityException extends SecurityAuthenticationException {

	public UnexpectedIdentityException(@Nullable String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("auth/unexpected-identity");
	}

	@Override
	public String getTitle() {
		return "Unexpected identity headers";
	}

}
