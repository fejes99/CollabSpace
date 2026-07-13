package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.InvalidInternalTokenException;
import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTokenFilter")
class InternalTokenFilterTest {

	private static final String TOKEN_HEADER = "X-Internal-Token";

	private final ListAppender<ILoggingEvent> loggingList = new ListAppender<>();

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private FilterChain filterChain;

	@Mock
	private ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	private InternalTokenFilter filter;

	private Logger rootLogger;

	@BeforeEach
	void setUp() {
		filter = new InternalTokenFilter(new InternalTokenProperties("expected-token"), problemDetailsSecurityHandler);
		rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		loggingList.start();
		rootLogger.addAppender(loggingList);
	}

	@AfterEach
	void tearDown() {
		rootLogger.detachAppender(loggingList);
		loggingList.stop();
		MDC.clear();
	}

	@Test
	@DisplayName("passes the request through when the token matches")
	void passesRequestThroughWhenTokenMatches() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/auth/register");
		when(request.getHeader(TOKEN_HEADER)).thenReturn("expected-token");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("rejects when the token header is missing")
	void rejectsWhenTokenIsMissing() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/auth/register");
		when(request.getHeader(TOKEN_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(InvalidInternalTokenException.class));
	}

	@Test
	@DisplayName("rejects when the token is wrong")
	void rejectsWhenTokenIsWrong() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/auth/register");
		when(request.getHeader(TOKEN_HEADER)).thenReturn("wrong-token");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(InvalidInternalTokenException.class));
	}

	@Test
	@DisplayName("bypasses the token check for .well-known paths")
	void bypassesTokenCheckForWellKnownPaths() throws Exception {
		when(request.getRequestURI()).thenReturn("/.well-known/jwks.json");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("bypasses the token check for Swagger UI and its OpenAPI JSON")
	void bypassesTokenCheckForDevToolingPaths() throws Exception {
		when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("does not bypass the token check for a path that merely starts with a dev-tooling prefix")
	void doesNotBypassTokenCheckForLookalikeDevToolingPath() throws Exception {
		when(request.getRequestURI()).thenReturn("/swagger-uikit-asset");
		when(request.getHeader("X-Internal-Token")).thenReturn("wrong-token");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(InvalidInternalTokenException.class));
	}

	@Test
	@DisplayName("bypasses the token check for the readiness probe from loopback")
	void bypassesTokenCheckForReadinessFromLoopback() throws Exception {
		when(request.getRequestURI()).thenReturn("/actuator/health/readiness");
		when(request.getRemoteAddr()).thenReturn("127.0.0.1");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("does not bypass the token check for the readiness probe from a non-loopback origin")
	void doesNotBypassTokenCheckForReadinessFromNonLoopback() throws Exception {
		when(request.getRequestURI()).thenReturn("/actuator/health/readiness");
		when(request.getRemoteAddr()).thenReturn("203.0.113.5");
		when(request.getHeader(TOKEN_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(InvalidInternalTokenException.class));
	}

	@Test
	@DisplayName("logs event=internal_token_invalid with ip, correlationId, and path on rejection")
	void logsInternalTokenInvalidWithRequiredFieldsOnRejection() throws Exception {
		MDC.put("correlationId", "trace-123");
		when(request.getRequestURI()).thenReturn("/v1/auth/register");
		when(request.getRemoteAddr()).thenReturn("198.51.100.7");
		when(request.getHeader(TOKEN_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		assertThat(loggingList.list).anyMatch(event -> event.getFormattedMessage()
			.equals("event=internal_token_invalid ip=198.51.100.7 correlationId=trace-123 path=/v1/auth/register"));
	}

}
