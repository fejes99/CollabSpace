package com.collabspace.authworkspace.adapter.in.rest.health;

import com.collabspace.authworkspace.support.JwtTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = { "spring.flyway.enabled=false",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect", "spring.jpa.hibernate.ddl-auto=none",
		"spring.data.redis.connect-timeout=500ms", "spring.data.redis.timeout=500ms" })
@AutoConfigureMockMvc
@Import({ RedisHealthCheckDownIntegrationTest.PostgresOnlyConfig.class, JwtTestConfiguration.class })
@DisplayName("GET /actuator/health — Redis down")
class RedisHealthCheckDownIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("root health shows redis DOWN, but readiness (db-only) stays UP")
	void redisDownDoesNotAffectReadiness() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.redis.status").value("DOWN"));

		mvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	static class PostgresOnlyConfig {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:16-alpine");
		}

		// Deliberately unreachable: a closed local port, not a hostname that
		// might resolve unpredictably in CI.
		@Bean
		DynamicPropertyRegistrar redisProperties() {
			return registry -> registry.add("spring.data.redis.url", () -> "redis://localhost:1");
		}

	}

}
