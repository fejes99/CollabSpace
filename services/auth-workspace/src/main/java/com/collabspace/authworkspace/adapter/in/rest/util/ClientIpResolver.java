package com.collabspace.authworkspace.adapter.in.rest.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIpResolver {

	private ClientIpResolver() {
	}

	public static String resolve(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null) {
			String firstHop = forwardedFor.split(",")[0].trim();
			if (!firstHop.isBlank()) {
				return firstHop;
			}
		}
		return request.getRemoteAddr();
	}

}
