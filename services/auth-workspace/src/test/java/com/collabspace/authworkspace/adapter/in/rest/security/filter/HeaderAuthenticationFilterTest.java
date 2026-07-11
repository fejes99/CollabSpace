package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.WorkspaceAuthority;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.UnexpectedIdentityException;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeaderAuthenticationFilter")
class HeaderAuthenticationFilterTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String REGISTER_PATH = "/v1/auth/register";

	private static final String PROTECTED_PATH = "/v1/workspaces/123";

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private FilterChain filterChain;

	@Mock
	private ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	private HeaderAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		filter = new HeaderAuthenticationFilter(problemDetailsSecurityHandler);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("passes through anonymously when both headers are absent on an anonymous route")
	void passesAnonymouslyWhenBothHeadersAbsentOnAnonymousRoute() throws Exception {
		when(request.getRequestURI()).thenReturn(REGISTER_PATH);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("passes through anonymously for a .well-known path")
	void passesAnonymouslyForWellKnownPath() throws Exception {
		when(request.getRequestURI()).thenReturn("/.well-known/jwks.json");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);
	}

	@Test
	@DisplayName("rejects when X-User-Id is present but X-User-Workspaces is absent")
	void rejectsWhenUserIdPresentWorkspacesAbsent() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Workspaces is present but X-User-Id is absent")
	void rejectsWhenWorkspacesPresentUserIdAbsent() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(null);
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("[]");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when both headers are absent on a protected route")
	void rejectsWhenUserIdAbsentOnProtectedRoute() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn(null);
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when identity headers are unexpectedly present on an anonymous route")
	void rejectsWhenIdentityHeadersPresentOnAnonymousRoute() throws Exception {
		when(request.getRequestURI()).thenReturn(REGISTER_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("[]");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(UnexpectedIdentityException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Id is blank")
	void rejectsWhenUserIdIsBlank() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("[]");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Workspaces is blank")
	void rejectsWhenWorkspacesHeaderIsBlank() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Workspaces exceeds the 4KB size limit")
	void rejectsWhenWorkspacesHeaderExceedsSizeLimit() throws Exception {
		// Valid JSON on purpose -- a handful of entries with a padded role value, kept
		// well under the 200-entry limit, so this isolates the byte-size check from the
		// malformed-JSON and entry-count checks (which would otherwise also trigger).
		String longRole = "r".repeat(500);
		StringBuilder oversized = new StringBuilder("[");
		for (int i = 0; i < 10; i++) {
			if (i > 0) {
				oversized.append(",");
			}
			oversized.append("{\"workspaceId\":\"ws-")
				.append(i)
				.append("\",\"role\":\"")
				.append(longRole)
				.append("\"}");
		}
		oversized.append("]");

		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn(oversized.toString());

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Workspaces is not valid JSON")
	void rejectsWhenWorkspacesHeaderIsMalformedJson() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("{not valid json");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("rejects when X-User-Workspaces exceeds the 100-entry limit")
	void rejectsWhenWorkspacesExceedsEntryLimit() throws Exception {
		// 101 minimal entries (~3.9KB) stays under the 4KB size limit, isolating the
		// entry-count check -- 200 entries would have tripped the size check first.
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < 101; i++) {
			if (i > 0) {
				json.append(",");
			}
			json.append("{\"workspaceId\":\"ws-").append(i).append("\",\"role\":\"member\"}");
		}
		json.append("]");

		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn(json.toString());

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		verify(problemDetailsSecurityHandler).commence(eq(request), eq(response),
				any(MalformedIdentityHeadersException.class));
	}

	@Test
	@DisplayName("sets a pre-authenticated token and passes through for a valid protected request")
	void setsAuthenticationAndPassesThroughForValidRequest() throws Exception {
		when(request.getRequestURI()).thenReturn(PROTECTED_PATH);
		when(request.getHeader(USER_ID_HEADER)).thenReturn("user-1");
		when(request.getHeader(WORKSPACES_HEADER)).thenReturn("[{\"workspaceId\":\"ws-1\",\"role\":\"admin\"}]");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(problemDetailsSecurityHandler);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isInstanceOf(PreAuthenticatedAuthenticationToken.class);
		assertThat(authentication.getPrincipal()).isEqualTo("user-1");
		assertThat(authentication.getAuthorities()).hasSize(1);
		GrantedAuthority authority = authentication.getAuthorities().iterator().next();
		assertThat(authority).isEqualTo(new WorkspaceAuthority("ws-1", "admin"));
	}

}
