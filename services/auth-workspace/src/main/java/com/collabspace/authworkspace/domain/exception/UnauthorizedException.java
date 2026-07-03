package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public abstract class UnauthorizedException extends DomainException {

	protected UnauthorizedException(String message) {
		super(message);
	}

	public abstract URI getType();

}
