package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public abstract class NotFoundException extends DomainException {

	protected NotFoundException(String message) {
		super(message);
	}

	public abstract URI getType();

}
