package com.collabspace.authworkspace.adapter.in.rest.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpResolver {

	private ClientIpResolver() {
	}

	public static String resolve(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		return (forwardedFor != null && !forwardedFor.isBlank()) ? forwardedFor.split(",")[0].trim()
				: request.getRemoteAddr();
	}

}
