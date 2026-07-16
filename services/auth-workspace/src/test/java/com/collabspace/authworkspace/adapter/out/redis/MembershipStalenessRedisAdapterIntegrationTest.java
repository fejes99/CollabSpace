package com.collabspace.authworkspace.adapter.out.redis;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MembershipStalenessRedisAdapter")
class MembershipStalenessRedisAdapterIntegrationTest {

	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
	@Import(TestContainersConfiguration.class)
	@Nested
	class AgainstRealRedis {

		@Autowired
		private MembershipStalenessRedisAdapter adapter;

		@Autowired
		private StringRedisTemplate redisTemplate;

		@AfterEach
		void flushKeys() {
			redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
		}

		@Test
		@DisplayName("returns empty when no marker exists for the user")
		void returnsEmptyWhenMarkerAbsent() {
			assertThat(adapter.findMembershipChangedAt(UUID.randomUUID())).isEmpty();
		}

		@Test
		@DisplayName("returns the written timestamp, truncated to seconds, after a write")
		void returnsWrittenTimestampAfterWrite() {
			UUID userId = UUID.randomUUID();
			Instant changedAt = Instant.parse("2026-06-04T10:00:00Z");

			adapter.markMembershipChanged(userId, changedAt);

			assertThat(adapter.findMembershipChangedAt(userId)).contains(changedAt);
		}

		@Test
		@DisplayName("sets a TTL on the marker, roughly matching the ADR-032 15+2 minute window")
		void writeSetsATtl() {
			UUID userId = UUID.randomUUID();

			adapter.markMembershipChanged(userId, Instant.now());

			Long ttlSeconds = redisTemplate.getExpire("membership-changed-at:" + userId);
			assertThat(ttlSeconds).isNotNull();
			assertThat(ttlSeconds).isGreaterThan(Duration.ofMinutes(16).toSeconds());
			assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofMinutes(17).toSeconds());
		}

	}

	@DisplayName("fail-open / fail-closed asymmetry")
	@Nested
	class FailureHandling {

		@Test
		@DisplayName("findMembershipChangedAt returns empty instead of throwing when Redis is unreachable")
		void readFailsOpenWhenRedisIsUnreachable() {
			MembershipStalenessRedisAdapter adapter = adapterAgainstUnreachableRedis();

			Optional<Instant> result = adapter.findMembershipChangedAt(UUID.randomUUID());

			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("markMembershipChanged throws instead of failing open when Redis is unreachable")
		void writeThrowsInsteadOfFailingOpenWhenRedisIsUnreachable() {
			// Deliberately asymmetric with the read side above -- this adapter has
			// exactly one caller (WorkspaceApplicationService's afterCommit), which
			// already owns the fail-open try/catch and its own
			// event=membership_marker_write_failed logging. See
			// MembershipStalenessRedisAdapter's own class comment.
			MembershipStalenessRedisAdapter adapter = adapterAgainstUnreachableRedis();

			assertThatThrownBy(() -> adapter.markMembershipChanged(UUID.randomUUID(), Instant.now()))
				.isInstanceOf(RedisConnectionFailureException.class);
		}

		private MembershipStalenessRedisAdapter adapterAgainstUnreachableRedis() {
			// Deliberately unreachable: a closed local port, not a hostname that might
			// resolve unpredictably in CI -- same trick used by
			// TokenBlocklistRedisAdapterIntegrationTest and
			// RedisHealthCheckDownIntegrationTest.
			LettuceConnectionFactory brokenConnectionFactory = new LettuceConnectionFactory("localhost", 1);
			brokenConnectionFactory.afterPropertiesSet();
			StringRedisTemplate brokenTemplate = new StringRedisTemplate(brokenConnectionFactory);
			brokenTemplate.afterPropertiesSet();
			return new MembershipStalenessRedisAdapter(brokenTemplate);
		}

	}

}
