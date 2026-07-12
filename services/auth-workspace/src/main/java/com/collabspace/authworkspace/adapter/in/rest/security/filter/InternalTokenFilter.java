package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.SecurityExemptPaths;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.InvalidInternalTokenException;
import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class InternalTokenFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

	private static final Set<String> LOOPBACK_EXEMPT_PATHS = Set.of("/actuator/health/readiness",
			"/actuator/health/liveness");

	private static final IpAddressMatcher IPV4_LOOPBACK = new IpAddressMatcher("127.0.0.1");

	private static final IpAddressMatcher IPV6_LOOPBACK = new IpAddressMatcher("::1");

	private final InternalTokenProperties internalTokenProperties;

	private final ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	public InternalTokenFilter(InternalTokenProperties internalTokenProperties,
			ProblemDetailsSecurityHandler problemDetailsSecurityHandler) {
		this.internalTokenProperties = internalTokenProperties;
		this.problemDetailsSecurityHandler = problemDetailsSecurityHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (SecurityExemptPaths.isWellKnownPath(request) || isLoopbackExempt(request)
				|| SecurityExemptPaths.isDevToolingPath(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String internalToken = request.getHeader("X-Internal-Token");
		if (!internalTokenProperties.token().equals(internalToken)) {
			// No userId available yet at this point in the chain --
			// HeaderAuthenticationFilter
			// runs after this one. See plan security-filter.md §4.
			log.warn("event=internal_token_invalid ip={} correlationId={} path={}", request.getRemoteAddr(),
					MDC.get("correlationId"), request.getRequestURI());
			problemDetailsSecurityHandler.commence(request, response,
					new InvalidInternalTokenException("X-Internal-Token missing or invalid"));
			return;
		}
		filterChain.doFilter(request, response);
	}

	// Path AND origin, not path alone -- see plan security-filter.md §3. Reads the raw
	// socket address via IpAddressMatcher, never X-Forwarded-For, which a client can
	// forge; server.forward-headers-strategy must stay NONE for this to be trustworthy.
	private boolean isLoopbackExempt(HttpServletRequest request) {
		return LOOPBACK_EXEMPT_PATHS.contains(request.getRequestURI())
				&& (IPV4_LOOPBACK.matches(request) || IPV6_LOOPBACK.matches(request));
	}

}
