package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import com.collabspace.authworkspace.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/workspaces")
class CreateWorkspaceIntegrationTest {

	private static final String WORKSPACE_URL = "/v1/workspaces";

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String ERRORS_FIELD_PATH = "$.errors[0].field";

	private static final String FIELD_NAME = "name";

	private static final String FIELD_DESCRIPTION = "description";

	private static final String VALID_REQUEST_BODY = """
			{ "name": "Engineering", "description": "Engineering workspace containing engineering documents" }
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	public CreateWorkspaceIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 201 with access token, workspace and role for a valid request")
	void createWorkspaceValidRequestReturns201WithTokenAndWorkspaceAndRole() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		MvcResult result = performCreateWorkspace(userId, VALID_REQUEST_BODY).andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.workspace.id").isNotEmpty())
			.andExpect(jsonPath("$.workspace.name").value("Engineering"))
			.andExpect(
					jsonPath("$.workspace.description").value("Engineering workspace containing engineering documents"))
			.andExpect(jsonPath("$.workspace.createdByUserId").value(userId))
			.andExpect(jsonPath("$.workspace.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.workspace.updatedAt").isNotEmpty())
			.andExpect(jsonPath("$.role").value("admin"))
			.andReturn();

		String responseBody = result.getResponse().getContentAsString();
		String workspaceId = JsonPath.read(responseBody, "$.workspace.id");
		String accessToken = JsonPath.read(responseBody, "$.accessToken");

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
	@DisplayName("trims leading and trailing whitespace from name")
	void createWorkspaceTrimsWhitespaceFromName() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		performCreateWorkspace(userId, """
				{ "name": "  Engineering  ", "description": "Engineering workspace containing engineering documents" }
				""").andExpect(status().isCreated()).andExpect(jsonPath("$.workspace.name").value("Engineering"));
	}

	@Test
	@DisplayName("returns 400 with errors array when name is blank")
	void createWorkspaceBlankNameReturns400WithErrorsArray() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		performCreateWorkspace(userId, """
				{ "name": "", "description": "Engineering workspace containing engineering documents" }
				""").andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_NAME));
	}

	@Test
	@DisplayName("returns 400 with errors array when name is missing")
	void createWorkspaceMissingNameReturns400WithErrorsArray() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		performCreateWorkspace(userId, """
				{ "description": "Engineering workspace containing engineering documents" }
				""").andExpect(status().isBadRequest()).andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_NAME));
	}

	@Test
	@DisplayName("returns 400 with problem detail when name exceeds limit")
	void createWorkspaceInvalidNameRequestReturns400WithProblemDetails() throws Exception {
		String tooLongName = "A".repeat(256);
		String body = String.format("""
				{ "name": "%s", "description": "Engineering workspace containing engineering documents" }
				""", tooLongName);
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		performCreateWorkspace(userId, body).andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_NAME));
	}

	@Test
	@DisplayName("returns 400 with problem detail when description exceeds limit")
	void createWorkspaceInvalidDescriptionRequestReturns400WithProblemDetails() throws Exception {
		String tooLongDescription = "A".repeat(2001);
		String body = String.format("""
				{ "name": "Engineering", "description": "%s" }
				""", tooLongDescription);
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		performCreateWorkspace(userId, body).andExpect(status().isBadRequest())
			.andExpect(jsonPath(ERRORS_FIELD_PATH).value(FIELD_DESCRIPTION));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void createWorkspaceMissingIdentityHeadersReturns401() throws Exception {
		mvc.perform(post(WORKSPACE_URL).header("X-Internal-Token", internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_REQUEST_BODY)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("returns 400 with problem detail when request body is missing")
	void createWorkspaceMissingBodyReturns400WithProblemDetails() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		mvc.perform(post(WORKSPACE_URL).header("X-Internal-Token", internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	@DisplayName("creates two separate workspaces on double-submit with identical name and description")
	void createWorkspaceDoubleSubmitCreatesTwoSeparateWorkspaces() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice@example.com");

		MvcResult first = performCreateWorkspace(userId, VALID_REQUEST_BODY).andExpect(status().isCreated())
			.andReturn();
		MvcResult second = performCreateWorkspace(userId, VALID_REQUEST_BODY).andExpect(status().isCreated())
			.andReturn();

		String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.workspace.id");
		String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.workspace.id");

		assertThat(firstId).isNotEqualTo(secondId);
	}

	private ResultActions performCreateWorkspace(String userId, String body) throws Exception {
		return mvc.perform(post(WORKSPACE_URL).header("X-Internal-Token", internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
