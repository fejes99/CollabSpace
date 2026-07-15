package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.adapter.in.rest.security.exception.SecurityAuthenticationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

@Component
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailsSecurityHandler.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		ProblemDetail problem = ProblemDetail.forStatus(401);

		// authException is one of our own SecurityAuthenticationException subtypes when a
		// security filter
		// (InternalTokenFilter/HeaderAuthenticationFilter/JwtBlocklistFilter)
		// rejected the request explicitly. It can also be a plain Spring Security
		// AuthenticationException (e.g. InsufficientAuthenticationException) when
		// anyRequest().authenticated() itself rejects a request none of our filters
		// treated as anonymous but also never populated -- that path carries no `type`.
		if (authException instanceof SecurityAuthenticationException securityException) {
			problem.setType(securityException.getType());
			problem.setTitle(securityException.getTitle());
			problem.setDetail(securityException.getMessage());
			log.warn("event=authentication_rejected type={} uri={}", securityException.getType(),
					request.getRequestURI());
		}
		else {
			problem.setTitle("Unauthorized");
			problem.setDetail(authException.getMessage());
			log.warn("event=authentication_rejected type={} uri={}", authException.getClass().getSimpleName(),
					request.getRequestURI());
		}
		problem.setInstance(URI.create(request.getRequestURI()));

		writeProblemDetail(response, problem);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		// Nothing throws AccessDeniedException yet -- PR 8's @PreAuthorize checks are the
		// first caller.
	}

	private void writeProblemDetail(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType("application/problem+json");
		response.getWriter().write(objectMapper.writeValueAsString(problem));
	}

}
