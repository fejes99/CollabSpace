package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public abstract class ConflictException extends DomainException {

	protected ConflictException(String message) {
		super(message);
	}

	public abstract URI getType();

}
