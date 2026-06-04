package com.collabspace.authworkspace.domain.exception;

import java.net.URI;

public abstract class DomainException extends RuntimeException {

	protected DomainException(String message) {
		super(message);
	}

	public abstract URI getType();

	public static URI errorType(String path) {
		return URI.create("https://errors.collabspace.io/" + path);
	}

}
