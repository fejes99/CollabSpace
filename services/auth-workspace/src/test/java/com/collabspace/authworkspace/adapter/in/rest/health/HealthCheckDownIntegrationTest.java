package com.collabspace.authworkspace.adapter.in.rest.health;

import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import com.collabspace.authworkspace.support.JwtTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = { "spring.datasource.url=jdbc:postgresql://localhost:1/invalid",
				"spring.datasource.hikari.initialization-fail-timeout=0",
				"spring.datasource.hikari.connection-timeout=500", "spring.flyway.enabled=false",
				"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
				"spring.jpa.hibernate.ddl-auto=none" })
@AutoConfigureMockMvc
@Import(JwtTestConfiguration.class)
@DisplayName("GET /actuator/health — database down")
class HealthCheckDownIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	InternalTokenProperties internalTokenProperties;

	@Test
	@DisplayName("returns 503 DOWN when database is unreachable")
	void healthReturnsServiceUnavailableWhenDbDown() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Internal-Token", internalTokenProperties.token()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.components.db.status").value("DOWN"));
	}

}
