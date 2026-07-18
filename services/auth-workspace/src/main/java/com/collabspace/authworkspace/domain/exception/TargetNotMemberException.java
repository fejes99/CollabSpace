package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public class TargetNotMemberException extends NotFoundException {

	public static final URI TYPE = errorType("workspace/target-not-a-member");

	public TargetNotMemberException() {
		super("Target user is not a member of this workspace.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
