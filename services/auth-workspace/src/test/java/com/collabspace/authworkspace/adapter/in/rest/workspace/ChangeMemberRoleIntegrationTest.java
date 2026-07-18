package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import com.collabspace.authworkspace.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("PATCH /v1/workspaces/{workspaceId}/members/{memberId}")
class ChangeMemberRoleIntegrationTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String IAT_HEADER = "X-JWT-Iat";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	ChangeMemberRoleIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 200 with the promoted role when an admin promotes a member to admin")
	void changeRolePromoteMemberReturns200WithUpdatedRole() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role1@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role1@example.com", "member");

		performChangeRole(adminId, workspaceId, memberId, "admin").andExpect(status().isOk())
			.andExpect(jsonPath("$.workspaceId").value(workspaceId))
			.andExpect(jsonPath("$.userId").value(memberId))
			.andExpect(jsonPath("$.role").value("admin"))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty())
			.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	@DisplayName("returns 200 with the unchanged role and no access token when the requested role already matches")
	void changeRoleNoOpReturns200WithUnchangedRoleAndNullAccessToken() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role2@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role2@example.com", "member");

		performChangeRole(adminId, workspaceId, memberId, "member").andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("member"))
			.andExpect(jsonPath("$.accessToken").doesNotExist());
	}

	@Test
	@DisplayName("returns 200 with a fresh access token when an admin demotes themselves and is not the sole admin")
	void changeRoleSelfDemotionReturns200WithFreshAccessToken() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role3@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		inviteAndGetId(adminId, workspaceId, "bob-role3@example.com", "admin");

		performChangeRole(adminId, workspaceId, adminId, "member").andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("member"))
			.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	@DisplayName("end-to-end: other-directed demotion returns 200 with no access token and stales the target's existing token")
	void changeRoleOtherDirectedDemotionReturns200AndTargetTokenGoesStale() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role4@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String coAdminId = inviteAndGetId(adminId, workspaceId, "bob-role4@example.com", "admin");
		long beforeDemotion = Instant.now().getEpochSecond() - 5;

		performChangeRole(adminId, workspaceId, coAdminId, "member").andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("member"))
			.andExpect(jsonPath("$.accessToken").doesNotExist());

		// Any authenticated request works -- MembershipStalenessFilter runs before the
		// controller on every route. Reusing create-workspace here for the same reason
		// InviteMemberIntegrationTest does: no second endpoint's worth of fixtures needed
		// just to prove the filter intercepts first.
		mvc.perform(post("/v1/workspaces").header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, coAdminId)
			.header(WORKSPACES_HEADER, "[]")
			.header(IAT_HEADER, String.valueOf(beforeDemotion))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Should not be created" }
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/claims-stale"));
	}

	@Test
	@DisplayName("returns 400 with errors array when role is blank")
	void changeRoleBlankRoleReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role5@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role5@example.com", "member");

		performChangeRole(adminId, workspaceId, memberId, "").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("role"));
	}

	@Test
	@DisplayName("returns 400 with errors array when role is not admin or member")
	void changeRoleInvalidRoleValueReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role6@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role6@example.com", "member");

		performChangeRole(adminId, workspaceId, memberId, "owner").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("role"));
	}

	@Test
	@DisplayName("returns 400 with validation/malformed-request when the request body is missing")
	void changeRoleMissingBodyReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role7@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role7@example.com", "member");

		mvc.perform(patch(changeRoleUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/malformed-request"));
	}

	@Test
	@DisplayName("returns 400 with validation/invalid-path-parameter when workspaceId isn't a valid UUID")
	void changeRoleMalformedWorkspaceIdReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role8@example.com");

		mvc.perform(patch(changeRoleUrl("not-a-uuid", UUID.randomUUID().toString()))
			.header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "admin" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"));
	}

	@Test
	@DisplayName("returns 400 with validation/invalid-path-parameter when memberId isn't a valid UUID")
	void changeRoleMalformedMemberIdReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role9@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		mvc.perform(patch(changeRoleUrl(workspaceId, "not-a-uuid")).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "admin" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void changeRoleMissingIdentityHeadersReturns401() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role10@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role10@example.com", "member");

		mvc.perform(patch(changeRoleUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "admin" }
					""")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("returns 403 authorization/not-a-member when the caller has no membership in the workspace")
	void changeRoleCallerNotAMemberReturns403NotAMember() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role11@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-role11@example.com", "member");
		String outsiderId = TestUsers.registerAndGetUserId(mvc, internalToken, "Eve", "eve-role11@example.com");

		mvc.perform(patch(changeRoleUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, outsiderId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "admin" }
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/not-a-member"));
	}

	@Test
	@DisplayName("returns 403 authorization/insufficient-role when the caller is a member but not an admin")
	void changeRoleCallerInsufficientRoleReturns403InsufficientRole() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role12@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String callerId = inviteAndGetId(adminId, workspaceId, "carol-role12@example.com", "member");
		String targetId = inviteAndGetId(adminId, workspaceId, "bob-role12@example.com", "member");

		mvc.perform(patch(changeRoleUrl(workspaceId, targetId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, callerId)
			.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"member\"}]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "admin" }
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/insufficient-role"));
	}

	@Test
	@DisplayName("returns 404 workspace/target-not-a-member when the target has no membership in the workspace")
	void changeRoleTargetNotMemberReturns404TargetNotAMember() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role13@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String outsiderId = TestUsers.registerAndGetUserId(mvc, internalToken, "Eve", "eve-role13@example.com");

		performChangeRole(adminId, workspaceId, outsiderId, "admin").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/target-not-a-member"));
	}

	@Test
	@DisplayName("returns 422 workspace/last-admin-invariant when the sole admin demotes themselves")
	void changeRoleSelfDemotingSoleAdminReturns422() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role14@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		performChangeRole(adminId, workspaceId, adminId, "member").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/last-admin-invariant"));
	}

	@Test
	@DisplayName("returns 200 when another admin demotes the workspace creator -- creator has no special protection here")
	void changeRoleCreatorDemotedByAnotherAdminReturns200() throws Exception {
		String creatorId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role15@example.com");
		String workspaceId = createWorkspaceAndGetId(creatorId);
		String coAdminId = inviteAndGetId(creatorId, workspaceId, "bob-role15@example.com", "admin");

		mvc.perform(patch(changeRoleUrl(workspaceId, creatorId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, coAdminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "member" }
					""")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("member"));
	}

	@Test
	@DisplayName("returns 403, not 200, when a non-admin member targets themselves with their own current role -- @PreAuthorize runs before the no-op short-circuit")
	void changeRoleNonAdminNoOpProbeReturns403NotShortCircuited() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-role16@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "carol-role16@example.com", "member");

		mvc.perform(patch(changeRoleUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, memberId)
			.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"member\"}]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "role": "member" }
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/insufficient-role"));
	}

	private String createWorkspaceAndGetId(String adminUserId) throws Exception {
		MvcResult result = mvc
			.perform(post("/v1/workspaces").header(INTERNAL_TOKEN_HEADER, internalToken)
				.header(USER_ID_HEADER, adminUserId)
				.header(WORKSPACES_HEADER, "[]")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "name": "Engineering" }
						"""))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.workspace.id");
	}

	private String inviteAndGetId(String adminId, String workspaceId, String email, String role) throws Exception {
		TestUsers.registerAndGetUserId(mvc, internalToken, "Invitee", email);

		MvcResult result = mvc
			.perform(post("/v1/workspaces/" + workspaceId + "/members").header(INTERNAL_TOKEN_HEADER, internalToken)
				.header(USER_ID_HEADER, adminId)
				.header(WORKSPACES_HEADER, adminMembership(workspaceId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "email": "%s", "role": "%s" }
						""", email, role)))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.invitedUserId");
	}

	private ResultActions performChangeRole(String adminId, String workspaceId, String memberId, String role)
			throws Exception {
		return mvc.perform(patch(changeRoleUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(String.format("""
					{ "role": "%s" }
					""", role)));
	}

	private String changeRoleUrl(String workspaceId, String memberId) {
		return "/v1/workspaces/" + workspaceId + "/members/" + memberId;
	}

	private String adminMembership(String workspaceId) {
		return "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]";
	}

}
