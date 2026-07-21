package com.collabspace.authworkspace.domain.exception.workspace;

import com.collabspace.authworkspace.domain.exception.ConflictException;

import java.net.URI;

public class AlreadyMemberException extends ConflictException {

	public static final URI TYPE = errorType("workspace/already-member");

	public AlreadyMemberException() {
		super("User already a member of workspace.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
