package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
import com.collabspace.authworkspace.support.TestContainersConfiguration;
import com.collabspace.authworkspace.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/refresh")
class RefreshIntegrationTest {

	private static final String LOGIN_URL = "/v1/auth/login";

	private static final String REFRESH_URL = "/v1/auth/refresh";

	private static final String WORKSPACE_URL = "/v1/workspaces";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String TEST_USER_NAME = "Alice";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	private final String internalToken;

	RefreshIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 200 with a new access token and a rotated HttpOnly refresh cookie for a valid refresh token")
	void refreshWithValidCookieReturns200WithNewAccessTokenAndRotatedCookie() throws Exception {
		Cookie originalCookie = login("alice-refresh1@example.com").refreshCookie();

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(originalCookie))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(cookie().exists(REFRESH_TOKEN_COOKIE))
			.andExpect(cookie().httpOnly(REFRESH_TOKEN_COOKIE, true))
			.andExpect(cookie().path(REFRESH_TOKEN_COOKIE, "/v1/auth"))
			.andExpect(cookie().maxAge(REFRESH_TOKEN_COOKIE, 604800));
	}

	@Test
	@DisplayName("rotated refresh cookie has a different value than the one presented")
	void refreshRotatedCookieValueDiffersFromOriginal() throws Exception {
		Cookie originalCookie = login("alice-refresh2@example.com").refreshCookie();

		MvcResult result = mvc
			.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(originalCookie))
			.andExpect(status().isOk())
			.andReturn();

		Cookie newCookie = result.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(newCookie).isNotNull();
		assertThat(newCookie.getValue()).isNotEqualTo(originalCookie.getValue());
	}

	@Test
	@DisplayName("reusing the original cookie after it has been rotated returns 401")
	void refreshWithAlreadyRotatedCookieReturns401() throws Exception {
		Cookie originalCookie = login("alice-refresh3@example.com").refreshCookie();

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(originalCookie))
			.andExpect(status().isOk());

		// Same cookie value, presented a second time -- the first refresh already
		// deleted this row and rotated it, so this must be rejected exactly like any
		// other invalid token, not silently accepted a second time.
		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(originalCookie))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-invalid"));
	}

	@Test
	@DisplayName("the new access token re-derives current memberships from the database, not the old token's claims")
	void refreshedAccessTokenReflectsMembershipsCreatedAfterLogin() throws Exception {
		LoggedInUser loggedInUser = login("alice-refresh4@example.com");
		String userId = loggedInUser.userId();
		Cookie originalCookie = loggedInUser.refreshCookie();

		// Workspace created AFTER login -- the access token minted at login time has no
		// memberships claim for it. Simulates the caller's identity via raw headers
		// (no JWT authorizer running locally), same pattern as the workspace
		// integration tests.
		MvcResult createResult = mvc
			.perform(post(WORKSPACE_URL).header("X-Internal-Token", internalToken)
				.header(USER_ID_HEADER, userId)
				.header(WORKSPACES_HEADER, "[]")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "name": "Engineering" }
						"""))
			.andExpect(status().isCreated())
			.andReturn();
		String workspaceId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.workspace.id");

		MvcResult refreshResult = mvc
			.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(originalCookie))
			.andExpect(status().isOk())
			.andReturn();

		String accessToken = JsonPath.read(refreshResult.getResponse().getContentAsString(), "$.accessToken");
		SignedJWT jwt = SignedJWT.parse(accessToken);
		String membershipsClaim = jwt.getJWTClaimsSet().getStringClaim("memberships");
		List<Map<String, Object>> memberships = objectMapper.readValue(membershipsClaim, new TypeReference<>() {
		});

		assertThat(memberships).anySatisfy(membership -> {
			assertThat(membership).containsEntry("workspaceId", workspaceId);
			assertThat(membership).containsEntry("role", "admin");
		});
	}

	@Test
	@DisplayName("returns 401 with auth/refresh-token-invalid when no refresh_token cookie is present")
	void refreshWithMissingCookieReturns401() throws Exception {
		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-invalid"));
	}

	@Test
	@DisplayName("returns 401 with auth/refresh-token-invalid when the cookie value matches no stored token")
	void refreshWithUnknownCookieValueReturns401() throws Exception {
		Cookie garbageCookie = new Cookie(REFRESH_TOKEN_COOKIE, "this-value-was-never-issued-by-login");

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(garbageCookie))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-invalid"));
	}

	@Test
	@DisplayName("returns 401 with auth/refresh-token-invalid when the cookie exceeds 256 bytes")
	void refreshWithOversizedCookieReturns401() throws Exception {
		Cookie oversizedCookie = new Cookie(REFRESH_TOKEN_COOKIE, "a".repeat(257));

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(oversizedCookie))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-invalid"));
	}

	@Test
	@DisplayName("returns 401 with auth/refresh-token-expired when the stored token has expired")
	void refreshWithExpiredTokenReturns401WithExpiredType() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, TEST_USER_NAME,
				"alice-refresh5@example.com");
		String rawToken = "already-expired-raw-token-value";
		refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), UUID.fromString(userId),
				CryptoUtils.sha256Hex(rawToken), Instant.now().minusSeconds(700000), Instant.now().minusSeconds(1),
				Optional.empty(), Optional.empty()));

		Cookie expiredCookie = new Cookie(REFRESH_TOKEN_COOKIE, rawToken);

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(expiredCookie))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/refresh-token-expired"));
	}

	private LoggedInUser login(String email) throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, TEST_USER_NAME, email);

		MvcResult result = mvc
			.perform(post(LOGIN_URL).header("X-Internal-Token", internalToken)
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
