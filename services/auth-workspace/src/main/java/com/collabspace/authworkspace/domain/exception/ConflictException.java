package com.collabspace.authworkspace.domain.exception;

public abstract class ConflictException extends DomainException {

	protected ConflictException(String message) {
		super(message);
	}

}
