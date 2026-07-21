package com.collabspace.authworkspace.adapter.in.rest.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class CurrentUserIdResolver {

	private CurrentUserIdResolver() {
	}

	public static UUID resolve() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			// SecurityConfig's anyRequest().authenticated() guarantees this never fires
			// --
			// if it does, the security config itself is broken, not this request.
			throw new IllegalStateException(
					"Authenticated request reached the controller with no Authentication in the SecurityContext");
		}
		return UUID.fromString(authentication.getName());
	}

}
