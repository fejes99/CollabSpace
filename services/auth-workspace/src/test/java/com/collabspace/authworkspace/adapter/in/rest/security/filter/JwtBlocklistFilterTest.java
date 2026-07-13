package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.TokenRevokedException;
import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
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
@DisplayName("JwtBlocklistFilter")
class JwtBlocklistFilterTest {

	private static final String JTI_HEADER = "X-JWT-Jti";

	private final ListAppender<ILoggingEvent> loggingList = new ListAppender<>();

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private FilterChain filterChain;

	@Mock
	private TokenBlocklistRepository tokenBlocklistRepository;

	@Mock
	private ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	private JwtBlocklistFilter filter;

	private Logger rootLogger;

	@BeforeEach
	void setUp() {
		filter = new JwtBlocklistFilter(tokenBlocklistRepository, problemDetailsSecurityHandler);
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
	@DisplayName("passes through without checking the blocklist when jti is absent")
	void passesThroughWhenJtiAbsent() throws Exception {
		when(request.getHeader(JTI_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(tokenBlocklistRepository);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("passes through when jti is present but not blocklisted")
	void passesThroughWhenJtiNotBlocklisted() throws Exception {
		when(request.getHeader(JTI_HEADER)).thenReturn("jti-1");
		when(tokenBlocklistRepository.isBlocklisted("jti-1")).thenReturn(false);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("rejects when jti is present in the blocklist")
	void rejectsWhenJtiIsBlocklisted() throws Exception {
		when(request.getHeader(JTI_HEADER)).thenReturn("jti-1");
		when(tokenBlocklistRepository.isBlocklisted("jti-1")).thenReturn(true);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response), any(TokenRevokedException.class));
	}

	@Test
	@DisplayName("logs event=blocklist_check_failed with jti, userId, ip, and correlationId on rejection")
	void logsBlocklistCheckFailedWithRequiredFieldsOnRejection() throws Exception {
		MDC.put("correlationId", "trace-456");
		when(request.getHeader(JTI_HEADER)).thenReturn("jti-1");
		when(request.getHeader("X-User-Id")).thenReturn("user-1");
		when(request.getRemoteAddr()).thenReturn("198.51.100.7");
		when(tokenBlocklistRepository.isBlocklisted("jti-1")).thenReturn(true);

		filter.doFilterInternal(request, response, filterChain);

		assertThat(loggingList.list).anyMatch(event -> event.getFormattedMessage()
			.equals("event=blocklist_check_failed jti=jti-1 userId=user-1 ip=198.51.100.7 correlationId=trace-456"));
	}

}
