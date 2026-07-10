package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/register — security filter chain behaviour")
class SecurityFilterChainIntegrationTest {

	private static final String REGISTER_URL = "/v1/auth/register";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	@Autowired
	MockMvc mvc;

	private final String internalToken;

	public SecurityFilterChainIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 201 when internal token is valid and no identity headers are present")
	void registerWithValidInternalTokenAndNoIdentityHeadersReturns201() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)
			.header("X-Internal-Token", internalToken)
			.content("""
					{ "name": "Alice", "email": "alice@example.com", "password": "password123" }
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.user.id").isNotEmpty())
			.andExpect(jsonPath("$.user.email").value("alice@example.com"))
			.andExpect(jsonPath("$.user.name").value("Alice"))
			.andExpect(jsonPath("$.user.createdAt").isNotEmpty())
			.andExpect(cookie().doesNotExist(REFRESH_TOKEN_COOKIE));
	}

}
