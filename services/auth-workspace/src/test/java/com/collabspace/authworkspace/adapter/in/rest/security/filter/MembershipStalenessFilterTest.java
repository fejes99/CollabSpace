package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.ClaimsStaleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipStalenessFilter")
class MembershipStalenessFilterTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String IAT_HEADER = "X-JWT-Iat";

	private final ListAppender<ILoggingEvent> loggingList = new ListAppender<>();

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private FilterChain filterChain;

	@Mock
	private MembershipStalenessRepository membershipStalenessRepository;

	@Mock
	private ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	private MembershipStalenessFilter filter;

	private Logger rootLogger;

	@BeforeEach
	void setUp() {
		filter = new MembershipStalenessFilter(membershipStalenessRepository, problemDetailsSecurityHandler);
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
	@DisplayName("passes through without checking the marker when X-User-Id is absent")
	void passesThroughWhenUserIdHeaderAbsent() throws Exception {
		when(request.getHeader(USER_ID_HEADER)).thenReturn(null);
		when(request.getHeader(IAT_HEADER)).thenReturn("1000");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("passes through without checking the marker when X-JWT-Iat is absent")
	void passesThroughWhenIatHeaderAbsent() throws Exception {
		when(request.getHeader(USER_ID_HEADER)).thenReturn(UUID.randomUUID().toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("logs event=membership_staleness_iat_header_missing when X-User-Id is present but X-JWT-Iat is absent")
	void logsIatHeaderMissingWhenUserIdPresentButIatAbsent() throws Exception {
		MDC.put("correlationId", "trace-456");
		UUID userId = UUID.randomUUID();
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(loggingList.list).anyMatch(event -> event.getFormattedMessage()
			.equals("event=membership_staleness_iat_header_missing userId=" + userId + " correlationId=trace-456"));
	}

	@Test
	@DisplayName("passes through when no marker exists for the user")
	void passesThroughWhenNoMarkerExistsForUser() throws Exception {
		UUID userId = UUID.randomUUID();
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn("1000");
		when(membershipStalenessRepository.findMembershipChangedAt(userId)).thenReturn(Optional.empty());

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("passes through when the token was issued after the membership change")
	void passesThroughWhenTokenIssuedAfterMembershipChange() throws Exception {
		UUID userId = UUID.randomUUID();
		Instant changedAt = Instant.ofEpochSecond(1000);
		Instant iat = Instant.ofEpochSecond(1001);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(String.valueOf(iat.getEpochSecond()));
		when(membershipStalenessRepository.findMembershipChangedAt(userId)).thenReturn(Optional.of(changedAt));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("rejects when the token was issued before the membership change")
	void rejectsWhenTokenIssuedBeforeMembershipChange() throws Exception {
		UUID userId = UUID.randomUUID();
		Instant changedAt = Instant.ofEpochSecond(1001);
		Instant iat = Instant.ofEpochSecond(1000);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(String.valueOf(iat.getEpochSecond()));
		when(membershipStalenessRepository.findMembershipChangedAt(userId)).thenReturn(Optional.of(changedAt));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response), any(ClaimsStaleException.class));
	}

	@Test
	@DisplayName("rejects when the token was issued in the same second as the membership change (>=, not >)")
	void rejectsWhenTokenIssuedInSameSecondAsMembershipChange() throws Exception {
		UUID userId = UUID.randomUUID();
		Instant sameSecond = Instant.ofEpochSecond(1000);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(String.valueOf(sameSecond.getEpochSecond()));
		when(membershipStalenessRepository.findMembershipChangedAt(userId)).thenReturn(Optional.of(sameSecond));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response), any(ClaimsStaleException.class));
	}

	@Test
	@DisplayName("rejects with MalformedIdentityHeadersException when X-User-Id isn't a valid UUID")
	void rejectsWhenUserIdHeaderIsMalformed() throws Exception {
		when(request.getHeader(USER_ID_HEADER)).thenReturn("not-a-uuid");
		when(request.getHeader(IAT_HEADER)).thenReturn("1000");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
		verifyNoInteractions(membershipStalenessRepository);
	}

	@Test
	@DisplayName("rejects with MalformedIdentityHeadersException when X-JWT-Iat isn't numeric")
	void rejectsWhenIatHeaderIsMalformed() throws Exception {
		UUID userId = UUID.randomUUID();
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn("not-a-number");
		when(membershipStalenessRepository.findMembershipChangedAt(userId))
			.thenReturn(Optional.of(Instant.ofEpochSecond(1000)));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("logs event=claims_stale_rejected with userId, jti, and correlationId on rejection")
	void logsClaimsStaleRejectedWithRequiredFieldsOnRejection() throws Exception {
		MDC.put("correlationId", "trace-789");
		UUID userId = UUID.randomUUID();
		Instant changedAt = Instant.ofEpochSecond(1001);
		Instant iat = Instant.ofEpochSecond(1000);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(userId.toString());
		when(request.getHeader(IAT_HEADER)).thenReturn(String.valueOf(iat.getEpochSecond()));
		when(request.getHeader("X-JWT-Jti")).thenReturn("jti-1");
		when(membershipStalenessRepository.findMembershipChangedAt(userId)).thenReturn(Optional.of(changedAt));

		filter.doFilterInternal(request, response, filterChain);

		assertThat(loggingList.list).anyMatch(event -> event.getFormattedMessage()
			.equals("event=claims_stale_rejected userId=" + userId + " jti=jti-1 correlationId=trace-789"));
	}

}
