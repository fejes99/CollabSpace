package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public class InvitedUserNotFoundException extends NotFoundException {

	public static final URI TYPE = errorType("workspace/user-not-found");

	public InvitedUserNotFoundException() {
		super("User not found.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
