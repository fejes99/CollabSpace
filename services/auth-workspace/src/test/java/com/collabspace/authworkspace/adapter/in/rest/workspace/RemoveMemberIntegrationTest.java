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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("DELETE /v1/workspaces/{workspaceId}/members/{memberId}")
class RemoveMemberIntegrationTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	RemoveMemberIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("returns 204 when an admin removes another member")
	void removeOtherDirectedReturns204() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm1@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-rm1@example.com", "member");

		performRemove(adminId, workspaceId, memberId).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("returns 204 when an admin removes themselves and is not the sole admin")
	void removeSelfNotSoleAdminReturns204() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm2@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String coAdminId = inviteAndGetId(adminId, workspaceId, "bob-rm2@example.com", "admin");

		performRemove(coAdminId, workspaceId, coAdminId).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("returns 400 with validation/invalid-path-parameter when workspaceId isn't a valid UUID")
	void removeMalformedWorkspaceIdReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm3@example.com");

		mvc.perform(delete(removeUrl("not-a-uuid", UUID.randomUUID().toString()))
			.header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"));
	}

	@Test
	@DisplayName("returns 400 with validation/invalid-path-parameter when memberId isn't a valid UUID")
	void removeMalformedMemberIdReturns400() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm4@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);

		mvc.perform(delete(removeUrl(workspaceId, "not-a-uuid")).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void removeMissingIdentityHeadersReturns401() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm5@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-rm5@example.com", "member");

		mvc.perform(delete(removeUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("returns 403 authorization/not-a-member when the caller has no membership in the workspace")
	void removeCallerNotAMemberReturns403NotAMember() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm6@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-rm6@example.com", "member");
		String outsiderId = TestUsers.registerAndGetUserId(mvc, internalToken, "Eve", "eve-rm6@example.com");

		mvc.perform(delete(removeUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, outsiderId)
			.header(WORKSPACES_HEADER, "[]"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/not-a-member"));
	}

	@Test
	@DisplayName("returns 403 authorization/insufficient-role when the caller is a member but not an admin")
	void removeCallerInsufficientRoleReturns403InsufficientRole() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm7@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String callerId = inviteAndGetId(adminId, workspaceId, "carol-rm7@example.com", "member");
		String targetId = inviteAndGetId(adminId, workspaceId, "bob-rm7@example.com", "member");

		mvc.perform(delete(removeUrl(workspaceId, targetId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, callerId)
			.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"member\"}]"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/authorization/insufficient-role"));
	}

	@Test
	@DisplayName("returns 404 workspace/target-not-a-member when the target has no membership in the workspace")
	void removeTargetNotMemberReturns404TargetNotAMember() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm8@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String outsiderId = TestUsers.registerAndGetUserId(mvc, internalToken, "Eve", "eve-rm8@example.com");

		performRemove(adminId, workspaceId, outsiderId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/target-not-a-member"));
	}

	@Test
	@DisplayName("returns 422 workspace/last-admin-invariant when a non-creator sole admin removes themselves")
	void removeSelfSoleAdminNonCreatorReturns422LastAdminInvariant() throws Exception {
		String creatorId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm9@example.com");
		String workspaceId = createWorkspaceAndGetId(creatorId);
		String coAdminId = inviteAndGetId(creatorId, workspaceId, "bob-rm9@example.com", "admin");
		// The creator steps out first so coAdminId becomes the sole admin without ever
		// tripping creator-self-removal -- isolates the last-admin-invariant path from
		// the
		// creator-self-removal path below, which would otherwise both apply to the same
		// caller and mask which one actually fired.
		performRemove(coAdminId, workspaceId, creatorId).andExpect(status().isNoContent());

		performRemove(coAdminId, workspaceId, coAdminId).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/last-admin-invariant"));
	}

	@Test
	@DisplayName("returns 422 workspace/creator-self-removal when the creator removes themselves, even as the sole admin")
	void removeCreatorSelfRemovalReturns422CreatorSelfRemoval() throws Exception {
		String creatorId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm10@example.com");
		String workspaceId = createWorkspaceAndGetId(creatorId);

		performRemove(creatorId, workspaceId, creatorId).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/creator-self-removal"));
	}

	@Test
	@DisplayName("returns 204 when another admin removes the creator")
	void removeCreatorByAnotherAdminReturns204() throws Exception {
		String creatorId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm11@example.com");
		String workspaceId = createWorkspaceAndGetId(creatorId);
		String coAdminId = inviteAndGetId(creatorId, workspaceId, "bob-rm11@example.com", "admin");

		performRemove(coAdminId, workspaceId, creatorId).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a repeated DELETE for an already-removed member returns 404, not a second 204")
	void removeRepeatedDeleteReturns404OnSecondCall() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm12@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberId = inviteAndGetId(adminId, workspaceId, "bob-rm12@example.com", "member");

		performRemove(adminId, workspaceId, memberId).andExpect(status().isNoContent());

		performRemove(adminId, workspaceId, memberId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/workspace/target-not-a-member"));
	}

	@Test
	@DisplayName("removing one member does not affect a different member's own membership")
	void removeOneMemberLeavesOtherMembershipsIntact() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-rm13@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId);
		String memberAId = inviteAndGetId(adminId, workspaceId, "bob-rm13@example.com", "member");
		String memberBId = inviteAndGetId(adminId, workspaceId, "carol-rm13@example.com", "member");

		performRemove(adminId, workspaceId, memberAId).andExpect(status().isNoContent());

		// memberB only succeeds here if their row survived memberA's DELETE -- the query
		// scopes on (workspaceId, userId), so this is a real check on delete precision,
		// not
		// a tautology.
		performRemove(adminId, workspaceId, memberBId).andExpect(status().isNoContent());
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

	private ResultActions performRemove(String adminId, String workspaceId, String memberId) throws Exception {
		return mvc.perform(delete(removeUrl(workspaceId, memberId)).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, adminMembership(workspaceId)));
	}

	private String removeUrl(String workspaceId, String memberId) {
		return "/v1/workspaces/" + workspaceId + "/members/" + memberId;
	}

	private String adminMembership(String workspaceId) {
		return "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]";
	}

}
