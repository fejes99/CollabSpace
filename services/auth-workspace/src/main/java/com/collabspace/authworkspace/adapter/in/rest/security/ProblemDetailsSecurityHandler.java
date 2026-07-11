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
		SecurityAuthenticationException securityException = (SecurityAuthenticationException) authException;

		ProblemDetail problem = ProblemDetail.forStatus(401);
		problem.setType(securityException.getType());
		problem.setTitle(securityException.getTitle());
		problem.setDetail(securityException.getMessage());
		problem.setInstance(URI.create(request.getRequestURI()));

		log.warn("event=authentication_rejected type={} uri={}", securityException.getType(), request.getRequestURI());

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
