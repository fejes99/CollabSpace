package com.collabspace.authworkspace.domain.exception.workspace;

import com.collabspace.authworkspace.domain.exception.DomainException;

import java.net.URI;

public class CreatorSelfRemovalException extends DomainException {

	public static final URI TYPE = errorType("workspace/creator-self-removal");

	public CreatorSelfRemovalException() {
		super("The workspace creator cannot remove their own membership.");
	}

	@Override
	public URI getType() {
		return TYPE;
	}

}
