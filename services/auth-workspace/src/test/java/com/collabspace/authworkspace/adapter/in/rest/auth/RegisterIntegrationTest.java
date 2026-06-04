package com.collabspace.authworkspace.adapter.in.rest.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/auth/register")
class RegisterIntegrationTest {

	private static final String REGISTER_URL = "/v1/auth/register";

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("returns 201 with access token and user for a valid request")
	void registerValidRequestReturns201WithTokenAndUser() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "alice@example.com", "password": "password123" }
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.user.id").isNotEmpty())
			.andExpect(jsonPath("$.user.email").value("alice@example.com"))
			.andExpect(jsonPath("$.user.name").value("Alice"))
			.andExpect(jsonPath("$.user.createdAt").isNotEmpty());
	}

	@Test
	@DisplayName("normalises email to lowercase in the response")
	void registerEmailNormalisedResponseContainsLowercaseEmail() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "Alice@EXAMPLE.COM", "password": "password123" }
				""")).andExpect(status().isCreated()).andExpect(jsonPath("$.user.email").value("alice@example.com"));
	}

	@Test
	@DisplayName("returns 400 with errors array when email format is invalid")
	void registerInvalidEmailFormatReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "not-an-email", "password": "password123" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("email"));
	}

	@Test
	@DisplayName("returns 400 with errors array when password is too short")
	void registerPasswordTooShortReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "alice2@example.com", "password": "abc" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("password"));
	}

	@Test
	@DisplayName("returns 400 when request body is missing")
	void registerMissingBodyReturns400() throws Exception {
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
	}

}
