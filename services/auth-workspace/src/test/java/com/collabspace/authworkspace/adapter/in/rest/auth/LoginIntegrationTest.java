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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/login")
class LoginIntegrationTest {

	private static final String LOGIN_URL = "/v1/auth/login";

	private static final String REGISTER_URL = "/v1/auth/register";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	private static final String ERRORS_FIELD_PATH = "$.errors[0].field";

	private static final String FIELD_EMAIL = "email";

	private static final String FIELD_PASSWORD = "password";

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("returns 200 with access token, user, and HttpOnly refresh cookie for valid credentials")
	void loginWithValidCredentialsReturns200WithTokenAndUser() throws Exception {
		registerAlice();

		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@example.com", "password": "password123" }
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.user.id").isNotEmpty())
			.andExpect(jsonPath("$.user.email").value("alice@example.com"))
			.andExpect(jsonPath("$.user.name").value("Alice"))
			.andExpect(jsonPath("$.user.createdAt").isNotEmpty())
			.andExpect(cookie().exists(REFRESH_TOKEN_COOKIE))
			.andExpect(cookie().httpOnly(REFRESH_TOKEN_COOKIE, true))
			.andExpect(cookie().path(REFRESH_TOKEN_COOKIE, "/auth"))
			.andExpect(cookie().maxAge(REFRESH_TOKEN_COOKIE, 604800));
	}

	@Test
	@DisplayName("returns 401 with problem detail when password is wrong")
	void loginWrongPasswordReturns401WithProblemDetails() throws Exception {
		registerAlice();

		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@example.com", "password": "wrongpassword" }
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("returns 400 with problem detail when request body is missing")
	void loginMissingBodyReturns400WithProblemDetails() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("normalises email to lowercase in the response")
	void loginEmailNormalisedResponseContainsLowercaseEmail() throws Exception {
		registerAlice();

		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@EXAMPLE.com", "password": "password123" }
				""")).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value("alice@example.com"));
	}

	@Test
	@DisplayName("returns 400 with errors array when email is blank")
	void loginBlankEmailReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "", "password": "password123" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when email is missing")
	void loginMissingEmailReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "password": "password123" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when email format is invalid")
	void loginInvalidEmailFormatReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "not-an-email", "password": "password123" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is blank")
	void loginBlankPasswordReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@example.com", "password": "" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is missing")
	void loginMissingPasswordReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "alice@example.com" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 with errors array when password exceeds 128 characters")
	void loginPasswordTooLongReturns400WithErrorsArray() throws Exception {
		String tooLongPassword = "A".repeat(129);
		String body = String.format("""
				{ "email": "alice@example.com", "password": "%s" }
				""", tooLongPassword);

		mvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	private void registerAlice() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "alice@example.com", "password": "password123" }
				"""));
	}

}
