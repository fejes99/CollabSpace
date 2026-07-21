package com.collabspace.authworkspace.domain.exception.workspace;

import com.collabspace.authworkspace.domain.exception.DomainException;

import java.net.URI;

public class LastAdminInvariantException extends DomainException {

	public static final URI TYPE = errorType("workspace/last-admin-invariant");

	public LastAdminInvariantException() {
		super("This change would leave the workspace with no admins.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
