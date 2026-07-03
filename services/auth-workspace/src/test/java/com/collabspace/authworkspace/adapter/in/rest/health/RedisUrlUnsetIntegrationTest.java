package com.collabspace.authworkspace.adapter.in.rest.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Redis autoconfiguration — redis_url unset")
class RedisUrlUnsetIntegrationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class));

	@Test
	@DisplayName("falls back to default host, health reports DOWN, and logs a WARN")
	void unsetRedisUrlFallsBackToDefaultAndWarns(CapturedOutput output) {
		contextRunner.withBean(RedisHealthIndicator.class).run(context -> {
			assertThat(context).hasNotFailed();
			RedisHealthIndicator indicator = context.getBean(RedisHealthIndicator.class);
			assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
		});

		assertThat(output.getOut()).contains("event=redis_url_not_configured");
	}

}
