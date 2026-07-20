package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.support.PageWalker;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@Transactional
@DisplayName("GET /v1/workspaces")
class ListWorkspacesIntegrationTest {

	private static final String WORKSPACES_URL = "/v1/workspaces";

	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	ListWorkspacesIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	// Note on isolation: `workspaces` is queried system-wide, deliberately unscoped to
	// any caller (see the plan doc's §7). @Transactional rolls back each test's own
	// writes, but this is the first endpoint in the service that queries the whole
	// table rather than something caller/workspace-scoped -- so it's also the first
	// to be affected if any OTHER test class in the suite commits real rows without
	// rolling back (the concurrency tests can't use @Transactional, since they need
	// genuinely concurrent transactions). Tests below assert on the presence and
	// relative order of workspaces this test itself created, never on exact counts or
	// absolute positions, so they hold regardless of what else exists in the table.

	@Test
	@DisplayName("returns 200 with a well-formed page")
	void listReturnsAWellFormedPage() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list1@example.com");

		performList(userId, null, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray())
			.andExpect(jsonPath("$.pagination.limit").value(20))
			.andExpect(jsonPath("$.pagination.count").exists())
			.andExpect(jsonPath("$.pagination.hasNextPage").exists());
	}

	@Test
	@DisplayName("returns workspaces ordered oldest-first with id, name and memberCount -- no role field")
	void listReturnsWorkspacesInCreationOrderWithoutRoleField() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list2@example.com");
		String firstId = createWorkspaceAndGetId(userId, "Alpha-list2");
		String secondId = createWorkspaceAndGetId(userId, "Beta-list2");

		List<Map<String, Object>> data = listAllData(userId);

		int firstIndex = PageWalker.indexOfId(data, firstId);
		int secondIndex = PageWalker.indexOfId(data, secondId);
		assertThat(firstIndex).isGreaterThanOrEqualTo(0);
		assertThat(secondIndex).isGreaterThan(firstIndex);
		assertThat(data.get(firstIndex)).containsEntry("name", "Alpha-list2")
			.containsEntry("memberCount", 1)
			.doesNotContainKey("role");
		assertThat(data.get(secondIndex)).containsEntry("name", "Beta-list2");
	}

	@Test
	@DisplayName("any authenticated user sees every workspace, not just ones they belong to -- this endpoint is deliberately not self-scoped")
	void listAnyAuthenticatedUserSeesTheSameWorkspacesRegardlessOfMembership() throws Exception {
		String creatorId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list3@example.com");
		String workspaceId = createWorkspaceAndGetId(creatorId, "Engineering-list3");
		String unrelatedUserId = TestUsers.registerAndGetUserId(mvc, internalToken, "Zoe", "zoe-list3@example.com");

		List<Map<String, Object>> data = listAllData(unrelatedUserId);

		assertThat(PageWalker.indexOfId(data, workspaceId)).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("memberCount reflects an invited member, not just the creator")
	void listMemberCountReflectsInvitedMembers() throws Exception {
		String adminId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list4@example.com");
		String workspaceId = createWorkspaceAndGetId(adminId, "Engineering-list4");
		TestUsers.registerAndGetUserId(mvc, internalToken, "Bob", "bob-list4@example.com");

		mvc.perform(post("/v1/workspaces/" + workspaceId + "/members").header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, adminId)
			.header(WORKSPACES_HEADER, "[{\"workspaceId\":\"" + workspaceId + "\",\"role\":\"admin\"}]")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "bob-list4@example.com", "role": "member" }
					""")).andExpect(status().isCreated());

		List<Map<String, Object>> data = listAllData(adminId);
		int index = PageWalker.indexOfId(data, workspaceId);

		assertThat(index).isGreaterThanOrEqualTo(0);
		assertThat(data.get(index)).containsEntry("memberCount", 2);
	}

	@Test
	@DisplayName("returns 400 validation/invalid-request when limit is zero")
	void listLimitZeroReturns400() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list5@example.com");

		performList(userId, 0, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(containsString("validation/invalid-request")));
	}

	@Test
	@DisplayName("returns 400 validation/invalid-request when limit exceeds 100")
	void listLimitAboveMaxReturns400() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list6@example.com");

		performList(userId, 101, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(containsString("validation/invalid-request")));
	}

	@Test
	@DisplayName("returns 400 validation/invalid-request (not invalid-path-parameter) when limit isn't a number")
	void listLimitNonIntegerReturns400WithRequestParameterType() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list7@example.com");

		mvc.perform(get(WORKSPACES_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]")
			.param("limit", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(containsString("validation/invalid-request")));
	}

	@Test
	@DisplayName("returns 400 validation/invalid-cursor when after is not valid Base64")
	void listMalformedAfterCursorReturns400() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list8@example.com");

		performList(userId, null, "not-a-valid-cursor!!!").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(containsString("validation/invalid-cursor")));
	}

	@Test
	@DisplayName("returns 400 validation/invalid-cursor when after decodes to JSON missing required fields")
	void listAfterCursorMissingFieldsReturns400() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list9@example.com");
		String badCursor = Base64.getEncoder().encodeToString("{ \"createdAt\": \"2026-01-01T00:00:00Z\" }".getBytes());

		performList(userId, null, badCursor).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value(containsString("validation/invalid-cursor")));
	}

	@Test
	@DisplayName("paginates across pages using nextCursor: each created workspace appears exactly once, in creation order")
	void listPaginatesUsingNextCursorAcrossPages() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list10@example.com");
		String firstId = createWorkspaceAndGetId(userId, "Alpha-list10");
		String secondId = createWorkspaceAndGetId(userId, "Beta-list10");
		String thirdId = createWorkspaceAndGetId(userId, "Gamma-list10");

		// limit=2 forces at least two real page fetches to reach all three, genuinely
		// exercising nextCursor rather than fitting everything on one page.
		List<Map<String, Object>> data = listAllPages(userId, 2);

		int firstIndex = PageWalker.indexOfId(data, firstId);
		int secondIndex = PageWalker.indexOfId(data, secondId);
		int thirdIndex = PageWalker.indexOfId(data, thirdId);
		assertThat(firstIndex).isGreaterThanOrEqualTo(0);
		assertThat(secondIndex).isGreaterThan(firstIndex);
		assertThat(thirdIndex).isGreaterThan(secondIndex);
		// Each id exactly once -- a page-boundary bug that double-includes a row across
		// adjacent pages wouldn't be caught by the ordering assertions above alone.
		for (String id : List.of(firstId, secondId, thirdId)) {
			assertThat(data.stream().filter(item -> item.get("id").equals(id)).count()).as("count of id %s", id)
				.isEqualTo(1);
		}
	}

	@Test
	@DisplayName("defaults limit to 20 when omitted")
	void listDefaultsLimitTo20WhenOmitted() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", "alice-list12@example.com");

		performList(userId, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.pagination.limit").value(20));
	}

	@Test
	@DisplayName("returns 401 when identity headers are missing")
	void listUnauthenticatedReturns401() throws Exception {
		mvc.perform(get(WORKSPACES_URL).header(INTERNAL_TOKEN_HEADER, internalToken))
			.andExpect(status().isUnauthorized());
	}

	private ResultActions performList(String userId, Integer limit, String after) throws Exception {
		var request = get(WORKSPACES_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
			.header(USER_ID_HEADER, userId)
			.header(WORKSPACES_HEADER, "[]");
		if (limit != null) {
			request = request.param("limit", String.valueOf(limit));
		}
		if (after != null) {
			request = request.param("after", after);
		}
		return mvc.perform(request);
	}

	private List<Map<String, Object>> listAllData(String userId) throws Exception {
		return listAllPages(userId, 100);
	}

	private List<Map<String, Object>> listAllPages(String userId, int limit) throws Exception {
		return PageWalker.walkAll((pageLimit, after) -> {
			MvcResult result = performList(userId, pageLimit, after).andExpect(status().isOk()).andReturn();
			String body = result.getResponse().getContentAsString();
			List<Map<String, Object>> data = JsonPath.read(body, "$.data");
			boolean hasNextPage = JsonPath.read(body, "$.pagination.hasNextPage");
			String nextCursor = hasNextPage ? JsonPath.read(body, "$.pagination.nextCursor") : null;
			return new PageWalker.Page(data, hasNextPage, nextCursor);
		}, limit);
	}

	private String createWorkspaceAndGetId(String userId, String name) throws Exception {
		MvcResult result = mvc
			.perform(post(WORKSPACES_URL).header(INTERNAL_TOKEN_HEADER, internalToken)
				.header(USER_ID_HEADER, userId)
				.header(WORKSPACES_HEADER, "[]")
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "name": "%s" }
						""", name)))
			.andReturn();

		return JsonPath.read(result.getResponse().getContentAsString(), "$.workspace.id");
	}

}
