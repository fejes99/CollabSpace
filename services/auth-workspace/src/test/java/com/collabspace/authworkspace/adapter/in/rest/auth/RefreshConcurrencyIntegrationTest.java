package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import com.collabspace.authworkspace.support.TestUsers;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@DisplayName("POST /v1/auth/refresh -- concurrent refresh with the same token")
class RefreshConcurrencyIntegrationTest {

	private static final String LOGIN_URL = "/v1/auth/login";

	private static final String REFRESH_URL = "/v1/auth/refresh";

	private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

	@Autowired
	private MockMvc mvc;

	private final String internalToken;

	RefreshConcurrencyIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	// Deliberately not @Transactional, same reasoning as
	// RemoveMemberConcurrencyIntegrationTest: the two concurrent requests need their own
	// real transactions to actually race on the same refresh_tokens row -- a shared
	// test-managed transaction would put both calls on the same connection and defeat
	// the race entirely.
	@Test
	@DisplayName("two concurrent refresh calls with the same cookie: exactly one succeeds with 200, the other is rejected 401")
	void concurrentRefreshWithSameCookieExactlyOneSucceeds() throws Exception {
		String email = "concurrent-refresh-" + UUID.randomUUID() + "@example.com";
		Cookie refreshCookie = login(email);

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> firstRefresh = () -> {
				barrier.await();
				return performRefresh(refreshCookie).getResponse().getStatus();
			};
			Callable<Integer> secondRefresh = () -> {
				barrier.await();
				return performRefresh(refreshCookie).getResponse().getStatus();
			};

			Future<Integer> firstResult = executor.submit(firstRefresh);
			Future<Integer> secondResult = executor.submit(secondRefresh);

			assertThat(List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(200, 401);
		}
		finally {
			executor.shutdown();
		}
	}

	private MvcResult performRefresh(Cookie cookie) throws Exception {
		return mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(cookie)).andReturn();
	}

	private Cookie login(String email) throws Exception {
		TestUsers.registerAndGetUserId(mvc, internalToken, "Alice", email);

		MvcResult result = mvc
			.perform(post(LOGIN_URL).header("X-Internal-Token", internalToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(String.format("""
						{ "email": "%s", "password": "password123" }
						""", email)))
			.andReturn();

		Cookie cookie = result.getResponse().getCookie(REFRESH_TOKEN_COOKIE);
		assertThat(cookie).isNotNull();
		return cookie;
	}

}
