package com.collabspace.authworkspace.adapter.in.rest.security;

import jakarta.servlet.http.HttpServletRequest;

// Shared by InternalTokenFilter and HeaderAuthenticationFilter so "which paths are
// exempt from normal enforcement" can't drift apart between the two -- see plan
// security-filter.md §3.
public final class SecurityExemptPaths {

	private static final String WELL_KNOWN_PATH_PREFIX = "/.well-known/";

	// Local dev tooling only -- never routed through API Gateway in AWS (README:
	// "internal tooling only, not routed through API Gateway"). A browser loading
	// Swagger UI can't attach X-Internal-Token/X-User-Id until the page has already
	// loaded, so the page itself and its supporting requests must be exempt. Covers
	// /swagger-ui.html, /swagger-ui/index.html and its static assets, /v3/api-docs and
	// /v3/api-docs/swagger-config.
	private static final String[] DEV_TOOLING_PATH_PREFIXES = { "/swagger-ui", "/v3/api-docs" };

	private SecurityExemptPaths() {
	}

	// Combines both unconditional-by-path exemptions -- the one check both filters
	// actually need. Loopback-only exemptions (health readiness/liveness) stay in
	// InternalTokenFilter, since they also depend on caller origin, not path alone.
	public static boolean isPathExempt(HttpServletRequest request) {
		return isWellKnownPath(request) || isDevToolingPath(request);
	}

	private static boolean isWellKnownPath(HttpServletRequest request) {
		return request.getRequestURI().startsWith(WELL_KNOWN_PATH_PREFIX);
	}

	// startsWith alone would also match an unrelated route like /swagger-uikit-asset or
	// /v3/api-docs-export; require the match to end exactly at a segment or extension
	// boundary ('/', '.', or end of string) instead.
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
