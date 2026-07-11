package com.collabspace.authworkspace.adapter.out.redis;

import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBlocklistRedisAdapter")
class TokenBlocklistRedisAdapterIntegrationTest {

	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
	@Import(TestContainersConfiguration.class)
	@Nested
	class AgainstRealRedis {

		@Autowired
		private TokenBlocklistRedisAdapter adapter;

		@Autowired
		private StringRedisTemplate redisTemplate;

		@AfterEach
		void flushBlocklistKeys() {
			redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
		}

		@Test
		@DisplayName("returns false when the jti has no blocklist entry")
		void returnsFalseWhenKeyAbsent() {
			assertThat(adapter.isBlocklisted("jti-absent")).isFalse();
		}

		@Test
		@DisplayName("returns true when the jti is present in the blocklist")
		void returnsTrueWhenKeyPresent() {
			redisTemplate.opsForValue().set("blocklist:jti-present", "1");

			assertThat(adapter.isBlocklisted("jti-present")).isTrue();
		}

	}

	@DisplayName("fail-open behaviour")
	@Nested
	class FailOpen {

		@Test
		@DisplayName("returns false instead of throwing when Redis is unreachable")
		void returnsFalseWhenRedisIsUnreachable() {
			// Deliberately unreachable: a closed local port, not a hostname that might
			// resolve unpredictably in CI -- same trick used by
			// RedisHealthCheckDownIntegrationTest.PostgresOnlyConfig.
			LettuceConnectionFactory brokenConnectionFactory = new LettuceConnectionFactory("localhost", 1);
			brokenConnectionFactory.afterPropertiesSet();
			StringRedisTemplate brokenTemplate = new StringRedisTemplate(brokenConnectionFactory);
			brokenTemplate.afterPropertiesSet();

			TokenBlocklistRedisAdapter adapter = new TokenBlocklistRedisAdapter(brokenTemplate);

			assertThat(adapter.isBlocklisted("any-jti")).isFalse();

			brokenConnectionFactory.destroy();
		}

	}

}
