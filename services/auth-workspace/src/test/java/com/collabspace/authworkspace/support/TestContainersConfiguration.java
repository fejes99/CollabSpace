package com.collabspace.authworkspace.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
@Import(JwtTestConfiguration.class)
public class TestContainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}

	@Bean
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	}

	// Deliberately not @ServiceConnection: that mechanism wires Redis via a
	// RedisConnectionDetails bean override, bypassing spring.data.redis.url
	// entirely. RedisHealthIndicator reads that property directly to detect a
	// missing SSM/env value, so tests must populate the same property
	// production actually depends on.
	@Bean
	DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
		return registry -> registry.add("spring.data.redis.url",
				() -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
	}

}
