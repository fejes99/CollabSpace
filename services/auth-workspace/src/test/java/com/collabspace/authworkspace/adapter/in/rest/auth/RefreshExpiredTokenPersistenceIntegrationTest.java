package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Deliberately not @Transactional, same reasoning as RefreshConcurrencyIntegrationTest and
// ADR-034: a test wrapped in Spring's test-managed transaction shares the app code's
// connection, so it would "read its own writes" regardless of whether refresh()'s delete
// actually survives a real commit -- it would pass whether or not noRollbackFor is present.
// This test needs a real, physically committed (or rolled back) transaction to be meaningful.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@DisplayName("POST /v1/auth/refresh -- expired token row persistence across the rejecting transaction")
class RefreshExpiredTokenPersistenceIntegrationTest {

	private static final String REFRESH_URL = "/v1/auth/refresh";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	private final String internalToken;

	RefreshExpiredTokenPersistenceIntegrationTest(@Value("${INTERNAL_TOKEN}") String internalToken) {
		this.internalToken = internalToken;
	}

	@Test
	@DisplayName("expired token row is actually gone afterward, not just deleted-then-rolled-back")
	void expiredTokenRowIsActuallyDeletedNotRolledBack() throws Exception {
		String userId = TestUsers.registerAndGetUserId(mvc, internalToken, "Alice",
				"expired-persistence-" + UUID.randomUUID() + "@example.com");
		String rawToken = "expired-persistence-raw-token-" + UUID.randomUUID();
		String hashedToken = CryptoUtils.sha256Hex(rawToken);
		refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), UUID.fromString(userId), hashedToken,
				Instant.now().minusSeconds(700000), Instant.now().minusSeconds(1), Optional.empty(), Optional.empty()));

		Cookie expiredCookie = new Cookie("refresh_token", rawToken);

		mvc.perform(post(REFRESH_URL).header("X-Internal-Token", internalToken).cookie(expiredCookie))
			.andExpect(status().isUnauthorized());

		// No wrapping test transaction here, so this read reflects the real, committed
		// database state -- if refresh()'s delete had been rolled back along with the
		// 401 (the bug: @Transactional rolls back on any unchecked exception by
		// default), this row would still be here.
		assertThat(refreshTokenRepository.findByTokenHash(hashedToken)).isEmpty();
	}

}
