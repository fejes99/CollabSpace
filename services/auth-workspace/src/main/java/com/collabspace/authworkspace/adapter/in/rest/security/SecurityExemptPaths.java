package com.collabspace.authworkspace.adapter.in.rest.security;

import jakarta.servlet.http.HttpServletRequest;

// Shared so InternalTokenFilter and HeaderAuthenticationFilter can't drift apart
// on which paths are exempt -- see plan security-filter.md §3.
public final class SecurityExemptPaths {

	private static final String WELL_KNOWN_PATH_PREFIX = "/.well-known/";

	// Local dev tooling only -- Swagger UI can't attach X-Internal-Token until its own
	// page has loaded, so the page and its supporting requests must be exempt.
	private static final String[] DEV_TOOLING_PATH_PREFIXES = { "/swagger-ui", "/v3/api-docs" };

	private SecurityExemptPaths() {
	}

	// Loopback-only exemptions (health readiness/liveness) stay in InternalTokenFilter --
	// they also depend on caller origin, not path alone.
	public static boolean isPathExempt(HttpServletRequest request) {
		return isWellKnownPath(request) || isDevToolingPath(request);
	}

	private static boolean isWellKnownPath(HttpServletRequest request) {
		return request.getRequestURI().startsWith(WELL_KNOWN_PATH_PREFIX);
	}

	// Segment/extension boundary required so this doesn't also match an unrelated
	// route like /swagger-uikit-asset.
	private static boolean isDevToolingPath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		for (String prefix : DEV_TOOLING_PATH_PREFIXES) {
			if (matchesSegment(uri, prefix)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesSegment(String uri, String prefix) {
		if (!uri.startsWith(prefix)) {
			return false;
		}
		if (uri.length() == prefix.length()) {
			return true;
		}
		char boundary = uri.charAt(prefix.length());
		return boundary == '/' || boundary == '.';
	}

}
