package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public class MalformedIdentityHeadersException extends SecurityAuthenticationException {

	public MalformedIdentityHeadersException(@Nullable String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("auth/malformed-identity-headers");
	}

	@Override
	public String getTitle() {
		return "Malformed identity headers";
	}

}
