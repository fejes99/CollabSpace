package com.collabspace.authworkspace.adapter.in.rest.auth;

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
@DisplayName("POST /v1/auth/register")
class RegisterIntegrationTest {

	private static final String REGISTER_URL = "/v1/auth/register";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	private static final String ERRORS_FIELD_PATH = "$.errors[0].field";

	private static final String FIELD_NAME = "name";

	private static final String FIELD_EMAIL = "email";

	private static final String FIELD_PASSWORD = "password";

	@Autowired
	MockMvc mvc;

	private final String internalToken;

	public RegisterIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 201 with access token and user for a valid request")
	void registerValidRequestReturns201WithTokenAndUser() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
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

	@Test
	@DisplayName("normalises email to lowercase in the response")
	void registerEmailNormalisedResponseContainsLowercaseEmail() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "Alice@EXAMPLE.COM", "password": "password123" }
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.email").value("alice@example.com"))
			.andExpect(cookie().doesNotExist(REFRESH_TOKEN_COOKIE));
	}

	@Test
	@DisplayName("returns 400 with errors array when name is blank")
	void registerBlankNameReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "", "email": "alice@example.com", "password": "password123" }
					""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_NAME));
	}

	@Test
	@DisplayName("returns 400 with errors array when name is missing")
	void registerMissingNameReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "alice@example.com", "password": "password123" }
					""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_NAME));
	}

	@Test
	@DisplayName("returns 400 with errors array when email is blank")
	void registerBlankEmailReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "", "password": "password123" }
					""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when email is missing")
	void registerMissingEmailReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "password": "password123" }
					""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when email format is invalid")
	void registerInvalidEmailFormatReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "not-an-email", "password": "password123" }
					""")).andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_EMAIL));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is blank")
	void registerBlankPasswordReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "alice@example.com", "password": "" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is missing")
	void registerMissingPasswordReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "alice@example.com" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is too short")
	void registerPasswordTooShortReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Alice", "email": "alice@example.com", "password": "abc" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 with errors array when password exceeds 128 characters")
	void registerPasswordTooLongReturns400WithErrorsArray() throws Exception {
		String tooLongPassword = "A".repeat(129);
		String body = String.format("""
				{ "name": "Alice", "email": "alice@example.com", "password": "%s" }
				""", tooLongPassword);

		mvc.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_PASSWORD));
	}

	@Test
	@DisplayName("returns 400 when request body is missing")
	void registerMissingBodyReturns400() throws Exception {
		mvc.perform(
				post(REGISTER_URL).header("X-Internal-Token", internalToken).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest());
	}

}
