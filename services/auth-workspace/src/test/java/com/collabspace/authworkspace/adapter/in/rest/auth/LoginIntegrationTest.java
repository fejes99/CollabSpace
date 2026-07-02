package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/login")
class LoginIntegrationTest {

	private static final String LOGIN_URL = "/v1/auth/login";

	private static final String REGISTER_URL = "/v1/auth/register";

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("returns 200 with access token, user, and HttpOnly refresh cookie for valid credentials")
	void loginWithValidCredentialsReturns200WithTokenAndUser() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "alice@example.com", "password": "password123" }
				"""));

		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@example.com", "password": "password123" }
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.user.id").isNotEmpty())
			.andExpect(jsonPath("$.user.email").value("alice@example.com"))
			.andExpect(jsonPath("$.user.name").value("Alice"))
			.andExpect(jsonPath("$.user.createdAt").isNotEmpty())
			.andExpect(cookie().exists("refresh_token"))
			.andExpect(cookie().httpOnly("refresh_token", true))
			.andExpect(cookie().path("refresh_token", "/auth"));
	}

}
