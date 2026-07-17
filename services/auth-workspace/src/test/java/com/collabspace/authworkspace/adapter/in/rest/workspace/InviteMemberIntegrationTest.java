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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("POST /v1/workspaces/{workspaceId}/members")
class InviteMemberIntegrationTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String IAT_HEADER = "X-JWT-Iat";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	InviteMemberIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 201 with the created membership for a valid admin request")
	void inviteValidRequestReturns201WithCreatedMembership() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite1@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String invitedUserId = TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite1@example.com");

		performInvite(adminId, workspaceId, "bob-invite1@example.com", "member").andExpect(status().isCreated())
			.andExpect(jsonPath("$.invitedUserId").value(invitedUserId))
			.andExpect(jsonPath("$.email").value("bob-invite1@example.com"))
			.andExpect(jsonPath("$.role").value("member"))
			.andExpect(jsonPath("$.workspaceId").value(workspaceId))
			.andExpect(jsonPath("$.joinedAt").isNotEmpty());
	}

	@Test
	@DisplayName("defaults role to member when omitted from the request")
	void inviteOmittedRoleDefaultsToMember() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite2@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite2@example.com");

		mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "bob-invite2@example.com" }
					""")).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("member"));
	}

	@Test
	@DisplayName("honors an explicit admin role")
	void inviteExplicitAdminRoleIsHonored() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite3@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite3@example.com");

		performInvite(adminId, workspaceId, "bob-invite3@example.com", "admin").andExpect(status().isCreated())
			.andExpect(jsonPath("$.role").value("admin"));
	}

	@Test
	@DisplayName("returns 404 with workspace/user-not-found when the email is unregistered")
	void inviteUnregisteredEmailReturns404() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite4@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		performInvite(adminId, workspaceId, "nobody-invite4@example.com", "member").andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/user-not-found"));
	}

	@Test
	@DisplayName("returns 409 with workspace/already-member when the target is already a member")
	void inviteAlreadyMemberReturns409() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite5@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite5@example.com");
		performInvite(adminId, workspaceId, "bob-invite5@example.com", "member").andExpect(status().isCreated());

		performInvite(adminId, workspaceId, "bob-invite5@example.com", "member").andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/already-member"));
	}

	@Test
	@DisplayName("returns 400 with errors array when email is blank")
	void inviteBlankEmailReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite6@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		performInvite(adminId, workspaceId, "", "member").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("email"));
	}

	@Test
	@DisplayName("returns 400 with errors array when email exceeds 254 characters")
	void inviteEmailTooLongReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite14@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String tooLongEmail = "a".repeat(250) + "@example.com";

		performInvite(adminId, workspaceId, tooLongEmail, "member").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("email"));
	}

	@Test
	@DisplayName("returns 400 with validation/malformed-request when the request body is missing")
	void inviteMissingBodyReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite15@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/malformed-request"));
	}

	@Test
	@DisplayName("returns 400 with errors array when role is not admin or member")
	void inviteInvalidRoleReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite7@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite7@example.com");

		performInvite(adminId, workspaceId, "bob-invite7@example.com", "owner").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("role"));
	}

	@Test
	@DisplayName("accepts a mixed-case role, normalized before validation")
	void inviteMixedCaseRoleIsAccepted() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite8@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite8@example.com");

		performInvite(adminId, workspaceId, "bob-invite8@example.com", "Admin").andExpect(status().isCreated())
			.andExpect(jsonPath("$.role").value("admin"));
	}

	@Test
	@DisplayName("returns 400 with validation/invalid-path-parameter when workspaceId isn't a valid UUID")
	void inviteMalformedWorkspaceIdReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite13@example.com");

		mvc.perform(post(inviteUrl("not-a-uuid")).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "someone@example.com", "role": "member" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void inviteMissingIdentityHeadersReturns401() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite9@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "someone@example.com", "role": "member" }
					""")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("returns 403 authorization/not-a-member when the caller has no membership in the workspace")
	void inviteCallerNotAMemberReturns403() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite10@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String outsiderId = TestUsers.registerAndGetUserId(mvc, internalToken, "Eve", "eve-invite10@example.com");
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite10@example.com");

		mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, outsiderId)
			.header(WORKSPACES_HEADER, "[]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "bob-invite10@example.com", "role": "member" }
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/not-a-member"));
	}

	@Test
	@DisplayName("returns 403 authorization/insufficient-role when the caller is a member but not an admin")
	void inviteCallerInsufficientRoleReturns403() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite11@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = TestUsers.registerAndGetUserId(mvc, internalToken, "Carol", "carol-invite11@example.com");
		performInvite(adminId, workspaceId, "carol-invite11@example.com", "member").andExpect(status().isCreated());
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite11@example.com");

		mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, memberId)
			.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"member\"}]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "bob-invite11@example.com", "role": "member" }
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/insufficient-role"));
	}

	@Test
	@DisplayName("end-to-end: a token issued before the invite is rejected claims-stale on the invited user's next request")
	void inviteWritesMarkerThatRejectsAStaleTokenOnAnySubsequentRequest() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-invite12@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String invitedUserId = TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-invite12@example.com");
		long beforeInvite = Instant.now().getEpochSecond() - 5;

		performInvite(adminId, workspaceId, "bob-invite12@example.com", "member").andExpect(status().isCreated());

		// Any authenticated request works -- MembershipStalenessFilter runs before the
		// controller on every route. Reusing create-workspace here rather than
		// building a second endpoint's worth of fixtures just to prove the filter
		// intercepts first.
		mvc.perform(post("/v1/workspaces").header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, invitedUserId)
			.header(WORKSPACES_HEADER, "[]")
			.header(IAT_HEADER, String.valueOf(beforeInvite))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "name": "Should not be created" }
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/claims-stale"));
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

	private ResultActions performInvite(String adminId, String workspaceId, String email, String role)
			throws Exception {
		return mvc.perform(post(inviteUrl(workspaceId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(String.format("""
					{ "email": "%s", "role": "%s" }
					""", email, role)));
	}

	private String inviteUrl(String workspaceId) {
		return "/v1/workspaces/" + workspaceId + "/members";
	}

	private String adminMembership(String workspaceId) {
		return "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]";
	}

}
