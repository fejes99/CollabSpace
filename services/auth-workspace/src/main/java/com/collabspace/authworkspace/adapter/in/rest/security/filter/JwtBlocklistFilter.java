package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.TokenRevokedException;
import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtBlocklistFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtBlocklistFilter.class);

	private static final String JTI_HEADER = "X-JWT-Jti";

	private final TokenBlocklistRepository tokenBlocklistRepository;

	private final ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	public JwtBlocklistFilter(TokenBlocklistRepository tokenBlocklistRepository,
			ProblemDetailsSecurityHandler problemDetailsSecurityHandler) {
		this.tokenBlocklistRepository = tokenBlocklistRepository;
		this.problemDetailsSecurityHandler = problemDetailsSecurityHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String jti = request.getHeader(JTI_HEADER);

		if (jti != null && tokenBlocklistRepository.isBlocklisted(jti)) {
			log.warn("event=blocklist_check_failed jti={} userId={} ip={} correlationId={}", jti,
					request.getHeader("X-User-Id"), request.getRemoteAddr(), MDC.get("correlationId"));
			problemDetailsSecurityHandler.commence(request, response,
					new TokenRevokedException("jti is present in the blocklist"));
			return;
		}

		filterChain.doFilter(request, response);
	}

}
