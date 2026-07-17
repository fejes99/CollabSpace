package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public class TokenRevokedException extends SecurityAuthenticationException {

	public TokenRevokedException(@Nullable String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("auth/token-revoked");
	}

	@Override
	public String getTitle() {
		return "Token revoked";
	}

}
