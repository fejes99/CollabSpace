package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@DisplayName("DELETE /v1/workspaces/{workspaceId}/members/{memberId} -- concurrent removals")
class RemoveMemberConcurrencyIntegrationTest {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private WorkspaceMembershipRepository workspaceMembershipRepository;

	private final String internalToken;

	RemoveMemberConcurrencyIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	// Deliberately not @Transactional, same reasoning as
	// ChangeMemberRoleConcurrencyIntegrationTest: the two concurrent requests need their
	// own
	// real transactions to actually race against Postgres's row lock (ADR-038) -- a
	// shared
	// test-managed transaction would put both calls on the same connection and defeat the
	// race entirely. Every identifier here is randomized so reruns against a live
	// container
	// never collide.
	@Test
	@DisplayName("two admins removing each other concurrently: exactly one succeeds with 204, the other is rejected 422, and exactly one admin remains")
	void removeConcurrentRemovalsOfDifferentAdminsLeavesExactlyOneAdmin() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String aliceId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice",
				"alice-rmrace-" + suffix + "@example.com");
		String workspaceId = createWorkspaceAndGetId(aliceId);
		String bobId = inviteAndGetId(aliceId, workspaceId, "bob-rmrace-" + suffix + "@example.com", "admin");

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> aliceRemovesBob = () -> {
				barrier.await();
				return performRemove(workspaceId, aliceId, bobId).getResponse().getStatus();
			};
			Callable<Integer> bobRemovesAlice = () -> {
				barrier.await();
				return performRemove(workspaceId, bobId, aliceId).getResponse().getStatus();
			};

			Future<Integer> aliceResult = executor.submit(aliceRemovesBob);
			Future<Integer> bobResult = executor.submit(bobRemovesAlice);

			assertThat(List.of(aliceResult.get(), bobResult.get())).containsExactlyInAnyOrder(204, 422);
		}
		finally {
			executor.shutdown();
		}

		List<WorkspaceMembership> remainingAdmins = workspaceMembershipRepository
			.findByWorkspaceId(UUID.fromString(workspaceId))
			.stream()
			.filter(membership -> membership.role() == WorkspaceRole.ADMIN)
			.toList();
		assertThat(remainingAdmins).hasSize(1);
	}

	private MvcResult performRemove(String workspaceId, String callerId, String targetId) throws Exception {
		return mvc
			.perform(delete("/v1/workspaces/" + workspaceId + "/members/" + targetId)
				.header(INTERNAL_TOKEN_HEADER, internalToken)
				.header(USER_ID_HEADER, callerId)
				.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]"))
			.andReturn();
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
				.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]")
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "email": "%s", "role": "%s" }
						""", email, role)))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.invitedUserId");
	}

}
