package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.adapter.out.persistence.auth.repository.UserJpaRepository;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// No @Transactional — these tests verify actual database state after committed transactions.
// @Transactional on the test class would defer INSERTs and prevent constraint violations
// from firing during the test. Tests clean up via @BeforeEach instead.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@DisplayName("POST /v1/auth/register — transactional behaviour")
class RegisterTransactionalIT {

	private static final String REGISTER_URL = "/v1/auth/register";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserJpaRepository userJpaRepository;

	@MockitoSpyBean
	JwtService jwtService;

	@BeforeEach
	void cleanDatabase() {
		userJpaRepository.deleteAll();
	}

	@Test
	@DisplayName("rolls back user insert when JWT signing fails")
	void registrationRollsBackUserInsertWhenJwtSigningFails() throws Exception {
		doThrow(new IllegalStateException("simulated signing failure")).when(jwtService)
			.issueAccessToken(anyString(), anyList());

		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Alice", "email": "rollback@example.com", "password": "password123" }
				""")).andExpect(status().isInternalServerError());

		assertThat(userJpaRepository.count()).isZero();
	}

	@Test
	@DisplayName("returns 409 problem detail when email is already registered")
	void registerDuplicateEmailReturns409ProblemDetail() throws Exception {
		String body = """
				{ "name": "Alice", "email": "dup@example.com", "password": "password123" }
				""";
		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());

		mvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/email-already-taken"))
			.andExpect(jsonPath("$.title").value("Conflict"))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.detail").value("Email address is already registered."));
	}

}
