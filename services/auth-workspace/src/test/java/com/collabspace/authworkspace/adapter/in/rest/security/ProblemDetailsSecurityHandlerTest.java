package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.adapter.in.rest.security.exception.ClaimsStaleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.InsufficientRoleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.InvalidInternalTokenException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.NotAMemberException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.TokenRevokedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProblemDetailsSecurityHandler")
class ProblemDetailsSecurityHandlerTest {

	@Mock
	private HttpServletRequest request;

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final ProblemDetailsSecurityHandler handler = new ProblemDetailsSecurityHandler();

	@Test
	@DisplayName("writes a 401 RFC 9457 body for an invalid internal token")
	void commenceWritesProblemDetailForInvalidInternalToken() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/auth/register");

		handler.commence(request, response, new InvalidInternalTokenException("X-Internal-Token missing or invalid"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/invalid-internal-token\"");
		assertThat(body).contains("\"title\":\"Invalid internal token\"");
		assertThat(body).contains("\"detail\":\"X-Internal-Token missing or invalid\"");
		assertThat(body).contains("\"instance\":\"/v1/auth/register\"");
	}

	@Test
	@DisplayName("writes a 401 RFC 9457 body for malformed identity headers")
	void commenceWritesProblemDetailForMalformedIdentityHeaders() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces/123");

		handler.commence(request, response, new MalformedIdentityHeadersException("X-User-Id must not be blank"));

		assertThat(response.getStatus()).isEqualTo(401);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/malformed-identity-headers\"");
		assertThat(body).contains("\"title\":\"Malformed identity headers\"");
		assertThat(body).contains("\"detail\":\"X-User-Id must not be blank\"");
	}

	@Test
	@DisplayName("writes a 401 RFC 9457 body for a revoked token")
	void commenceWritesProblemDetailForRevokedToken() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces/123");

		handler.commence(request, response, new TokenRevokedException("jti is present in the blocklist"));

		assertThat(response.getStatus()).isEqualTo(401);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/token-revoked\"");
		assertThat(body).contains("\"title\":\"Token revoked\"");
	}

	@Test
	@DisplayName("writes a 401 RFC 9457 body for stale membership claims")
	void commenceWritesProblemDetailForClaimsStale() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces");

		handler.commence(request, response, new ClaimsStaleException("Token issued before the last membership change"));

		assertThat(response.getStatus()).isEqualTo(401);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/claims-stale\"");
		assertThat(body).contains("\"title\":\"Claims stale\"");
		assertThat(body).contains("\"detail\":\"Token issued before the last membership change\"");
	}

	@Test
	@DisplayName("commence writes a 401 RFC 9457 body with a catalog type for a plain AuthenticationException")
	void commenceWritesProblemDetailForGenericAuthenticationException() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces");

		handler.commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));

		assertThat(response.getStatus()).isEqualTo(401);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/insufficient-authentication\"");
		assertThat(body).contains("\"title\":\"Unauthorized\"");
		assertThat(body).contains("\"detail\":\"Full authentication is required\"");
	}

	@Test
	@DisplayName("handle writes a 403 RFC 9457 body for a denied request")
	void handleWritesProblemDetailForAccessDenied() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces");

		handler.handle(request, response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/auth/access-denied\"");
		assertThat(body).contains("\"title\":\"Forbidden\"");
		assertThat(body).contains("\"detail\":\"denied\"");
		assertThat(body).contains("\"instance\":\"/v1/workspaces\"");
	}

	// The two tests below exist specifically to guard against the missing-else bug
	// found this session: handle() originally set the polymorphic type/title from the
	// `if` branch, then unconditionally overwrote them with the generic
	// auth/access-denied fallback right after, since there was no `else`. The test
	// above (a plain AccessDeniedException) can't catch that regression -- it would
	// pass either way. Only asserting a SecurityAccessDeniedException subtype's
	// specific type/title actually exercises the polymorphic branch.

	@Test
	@DisplayName("handle writes the specific not-a-member type, not the generic access-denied fallback")
	void handleWritesSpecificTypeForNotAMemberException() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces/123/members");

		handler.handle(request, response, new NotAMemberException("Not a member of workspace 123"));

		assertThat(response.getStatus()).isEqualTo(403);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/authorization/not-a-member\"");
		assertThat(body).contains("\"title\":\"Not a member\"");
		assertThat(body).doesNotContain("auth/access-denied");
	}

	@Test
	@DisplayName("handle writes the specific insufficient-role type, not the generic access-denied fallback")
	void handleWritesSpecificTypeForInsufficientRoleException() throws Exception {
		when(request.getRequestURI()).thenReturn("/v1/workspaces/123/members");

		handler.handle(request, response, new InsufficientRoleException("Requires role admin in workspace 123"));

		assertThat(response.getStatus()).isEqualTo(403);
		String body = response.getContentAsString();
		assertThat(body).contains("\"type\":\"https://errors.collabspace.io/authorization/insufficient-role\"");
		assertThat(body).contains("\"title\":\"Insufficient role\"");
		assertThat(body).doesNotContain("auth/access-denied");
	}

}
