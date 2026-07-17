package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;

import java.net.URI;

public class InsufficientRoleException extends SecurityAccessDeniedException {

	public InsufficientRoleException(String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("authorization/insufficient-role");
	}

	@Override
	public String getTitle() {
		return "Insufficient role";
	}

}
