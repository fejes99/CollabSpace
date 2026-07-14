package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/workspaces")
class CreateWorkspaceIntegrationTest {

	private static final String WORKSPACE_URL = "/v1/workspaces";

	private static final String REGISTER_URL = "/v1/auth/register";

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

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
		String userId = registerUser();

		MvcResult result = mvc.perform(post(WORKSPACE_URL).header("X-Internal-Token", internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Engineering", "description": "Engineering workspace containing engineering documents" }
					"""))
			.andExpect(status().isCreated())
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

	private String registerUser() throws Exception {
		MvcResult result = mvc
			.perform(post(REGISTER_URL).header("X-Internal-Token", internalToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "name": "Alice", "email": "alice@example.com", "password": "password123" }
						"""))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.user.id");
	}

}
