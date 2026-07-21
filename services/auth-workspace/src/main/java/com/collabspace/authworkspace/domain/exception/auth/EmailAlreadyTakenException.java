package com.collabspace.authworkspace.domain.exception.auth;

import com.collabspace.authworkspace.domain.exception.ConflictException;

import java.net.URI;

public class EmailAlreadyTakenException extends ConflictException {

	public static final URI TYPE = errorType("auth/email-already-taken");

	public EmailAlreadyTakenException() {
		super("Email address is already registered.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
