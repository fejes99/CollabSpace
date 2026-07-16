package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;
import org.jspecify.annotations.Nullable;

import java.net.URI;

public class ClaimsStaleException extends SecurityAuthenticationException {

	public ClaimsStaleException(@Nullable String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("auth/claims-stale");
	}

	@Override
	public String getTitle() {
		return "Claims stale";
	}

}
