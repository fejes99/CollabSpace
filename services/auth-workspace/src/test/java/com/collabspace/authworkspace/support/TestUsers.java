package com.collabspace.authworkspace.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Shared "I just need some registered user as setup" helper for integration tests that
// aren't themselves testing /v1/auth/register. Not used by RegisterTransactionalIntegrationTest,
// which tests register's own behavior directly with per-case bodies.
public final class TestUsers {

	private static final String REGISTER_URL = "/v1/auth/register";

	private TestUsers() {
	}

	public static String registerAndGetUserId(MockMvc mvc, String internalToken, String name, String email)
			throws Exception {
		MvcResult result = mvc
			.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "name": "%s", "email": "%s", "password": "password123" }
						""", name, email)))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.user.id");
	}

}
