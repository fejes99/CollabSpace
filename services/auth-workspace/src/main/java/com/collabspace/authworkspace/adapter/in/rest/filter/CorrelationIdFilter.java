package com.collabspace.authworkspace.adapter.in.rest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Must run before Spring Security's filter chain so correlationId is already in MDC
// when the security filters log rejections.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

	private static final String HEADER_NAME = "X-Correlation-ID";

	private static final String MDC_KEY = "correlationId";

	private static final int MAX_CORRELATION_ID_LENGTH = 64;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request);
		MDC.put(MDC_KEY, correlationId);
		response.setHeader(HEADER_NAME, correlationId);
		response.setHeader("Access-Control-Expose-Headers", HEADER_NAME);
		log.debug("{} {}", request.getMethod(), request.getRequestURI());

		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.clear();
		}
	}

	private String resolveCorrelationId(HttpServletRequest request) {
		String value = request.getHeader(HEADER_NAME);
		if (value == null || value.isBlank()) {
			return UUID.randomUUID().toString();
		}
		return value.length() > MAX_CORRELATION_ID_LENGTH ? value.substring(0, MAX_CORRELATION_ID_LENGTH) : value;
	}

}
