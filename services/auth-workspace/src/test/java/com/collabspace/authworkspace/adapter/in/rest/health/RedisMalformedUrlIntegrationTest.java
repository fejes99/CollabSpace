package com.collabspace.authworkspace.adapter.in.rest.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redis autoconfiguration — malformed redis_url")
class RedisMalformedUrlIntegrationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class));

	@Test
	@DisplayName("context fails to start when spring.data.redis.url is not a valid URI")
	void malformedRedisUrlFailsStartup() {
		contextRunner.withPropertyValues("spring.data.redis.url=not-a-valid-url")
			.run(context -> assertThat(context).hasFailed());
	}

}
