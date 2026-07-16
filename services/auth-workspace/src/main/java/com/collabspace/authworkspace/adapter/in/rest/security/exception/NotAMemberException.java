package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import com.collabspace.authworkspace.domain.exception.DomainException;

import java.net.URI;

public class NotAMemberException extends SecurityAccessDeniedException {

	public NotAMemberException(String msg) {
		super(msg);
	}

	@Override
	public URI getType() {
		return DomainException.errorType("authorization/not-a-member");
	}

	@Override
	public String getTitle() {
		return "Not a member";
	}

}
