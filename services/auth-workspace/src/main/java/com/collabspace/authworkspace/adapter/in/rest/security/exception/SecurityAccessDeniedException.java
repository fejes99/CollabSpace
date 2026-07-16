package com.collabspace.authworkspace.adapter.in.rest.security.exception;

import org.springframework.security.access.AccessDeniedException;

import java.net.URI;

public abstract class SecurityAccessDeniedException extends AccessDeniedException {

	public SecurityAccessDeniedException(String msg) {
		super(msg);
	}

	public abstract URI getType();

	public abstract String getTitle();

}
