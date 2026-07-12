package com.collabspace.authworkspace.adapter.in.rest.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

// Shared by InternalTokenFilter and HeaderAuthenticationFilter so "which paths are
// exempt from normal enforcement" can't drift apart between the two -- see plan
// security-filter.md §3.
public final class SecurityExemptPaths {

	private static final String WELL_KNOWN_PATH_PREFIX = "/.well-known/";

	// Local dev tooling only -- never routed through API Gateway in AWS (README:
	// "internal tooling only, not routed through API Gateway"). A browser loading
	// Swagger UI can't attach X-Internal-Token/X-User-Id until the page has already
	// loaded, so the page itself and its supporting requests must be exempt.
	private static final Set<String> DEV_TOOLING_PATH_PREFIXES = Set.of("/swagger-ui", "/v3/api-docs");

	private SecurityExemptPaths() {
	}

	public static boolean isWellKnownPath(HttpServletRequest request) {
		return request.getRequestURI().startsWith(WELL_KNOWN_PATH_PREFIX);
	}

	public static boolean isDevToolingPath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return DEV_TOOLING_PATH_PREFIXES.stream().anyMatch(uri::startsWith);
	}

}
