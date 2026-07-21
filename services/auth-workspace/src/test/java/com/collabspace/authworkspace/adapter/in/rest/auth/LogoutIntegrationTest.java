package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import com.collabspace.authworkspace.support.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/logout")
class LogoutIntegrationTest {

	private static final String LOGIN_URL = "/v1/auth/login";

	private static final String LOGOUT_URL = "/v1/auth/logout";

	private static final String REFRESH_URL = "/v1/auth/refresh";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String JTI_HEADER = "X-JWT-Jti";

	private static final String IAT_HEADER = "X-JWT-Iat";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	LogoutIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 200 and clears the refresh cookie for a valid session")
	void logoutWithValidCookieReturns200AndClearsCookie() throws Exception {
		LoggedInUser user = login("alice-logout1@example.com");

		performLogout(user.userId(), "jti-1", now(), user.refreshCookie()).andExpect(status().isOk())
			.andExpect(cookie().exists(REFRESH_TOKEN_COOKIE))
			.andExpect(cookie().value(REFRESH_TOKEN_COOKIE, ""))
			.andExpect(cookie().maxAge(REFRESH_TOKEN_COOKIE, 0))
			.andExpect(cookie().path(REFRESH_TOKEN_COOKIE, "/v1/auth"))
			.andExpect(cookie().httpOnly(REFRESH_TOKEN_COOKIE, true));
	}

	@Test
	@DisplayName("a logged-out refresh token can no longer be used to refresh")
	void logoutDeletesRefreshTokenSoRefreshAfterwardsFails() throws Exception {
		LoggedInUser user = login("alice-logout2@example.com");

		performLogout(user.userId(), "jti-2", now(), user.refreshCookie()).andExpect(status().isOk());

		mvc.perform(post(REFRESH_URL).header(INTERNAL_TOKEN_HEADER, internalToken).cookie(user.refreshCookie()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-invalid"));
	}

	@Test
	@DisplayName("returns 200 when no refresh_token cookie is presented")
	void logoutWithNoCookieReturns200() throws Exception {
		LoggedInUser user = login("alice-logout3@example.com");

		performLogout(user.userId(), "jti-3", now(), null).andExpect(status().isOk());
	}

	@Test
	@DisplayName("returns 200 when the cookie matches no stored refresh token (already logged out, duplicate request)")
	void logoutWithUnknownCookieReturns200() throws Exception {
		LoggedInUser user = login("alice-logout4@example.com");
		Cookie garbageCookie = new Cookie(REFRESH_TOKEN_COOKIE, "this-value-was-never-issued-by-login");

		performLogout(user.userId(), "jti-4", now(), garbageCookie).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a repeated call with the same jti is rejected 401 by the blocklist filter, not re-processed as a no-op")
	void logoutRetryWithSameJtiReturns401TokenRevoked() throws Exception {
		LoggedInUser user = login("alice-logout5@example.com");

		performLogout(user.userId(), "jti-5", now(), user.refreshCookie()).andExpect(status().isOk());

		// Same jti as the call above -- the first call already blocklisted it, so
		// JwtBlocklistFilter rejects this one before it ever reaches the controller's
		// own no-op handling. See plan §2/§6.
		performLogout(user.userId(), "jti-5", now(), null).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/token-revoked"));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void logoutMissingIdentityHeadersReturns401() throws Exception {
		mvc.perform(post(LOGOUT_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(JTI_HEADER, "jti-6")
			.header(IAT_HEADER, String.valueOf(now()))).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("returns 401 auth/malformed-identity-headers, not 500, when X-JWT-Jti is missing but the caller is otherwise authenticated")
	void logoutMissingJtiHeaderReturns401MalformedIdentityHeaders() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-logout7@example.com");

		// X-User-Id/X-User-Workspaces present (as MembershipStalenessFilter/
		// JwtBlocklistFilter both tolerate on their own), X-JWT-Jti absent -- simulates
		// exactly the partial claim-mapping regression this endpoint is the first to be
		// exposed to. See plan/review notes: this used to 500.
		mvc.perform(post(LOGOUT_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.header(IAT_HEADER, String.valueOf(now())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/malformed-identity-headers"));
	}

	@Test
	@DisplayName("returns 401 auth/malformed-identity-headers, not 500, when X-JWT-Iat is missing but the caller is otherwise authenticated")
	void logoutMissingIatHeaderReturns401MalformedIdentityHeaders() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-logout8@example.com");

		mvc.perform(post(LOGOUT_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.header(JTI_HEADER, "jti-8"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/malformed-identity-headers"));
	}

	private ResultActions performLogout(String userId, String jti, long iat, Cookie refreshCookie) throws Exception {
		var request = post(LOGOUT_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.header(JTI_HEADER, jti)
			.header(IAT_HEADER, String.valueOf(iat));
		if (refreshCookie != null) {
			request.cookie(refreshCookie);
		}
		return mvc.perform(request);
	}

	private long now() {
		return Instant.now().getEpochSecond();
	}

	private LoggedInUser login(String email) throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", email);

		MvcResult result = mvc
			.perform(post(LOGIN_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "email": "%s", "password": "password123" }
						""", email)))
			.andExpect(status().isOk())
			.andReturn();

		Cookie cookie = result.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(cookie).isNotNull();
		return new LoggedInUser(userId, cookie);
	}

	private record LoggedInUser(String userId, Cookie refreshCookie) {
	}

}
