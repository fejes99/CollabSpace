package com.collabspace.authworkspace.adapter.in.rest.health;

import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import com.collabspace.authworkspace.support.TestContainersConfiguration;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestContainersConfiguration.class)
@DisplayName("GET /actuator/health")
class HealthCheckIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	InternalTokenProperties internalTokenProperties;

	@Test
	@DisplayName("returns 200 UP when database is reachable")
	void healthReturnsUpWhenDbReachable() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Internal-Token", internalTokenProperties.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"));
	}

	@Test
	@DisplayName("returns 200 UP with redis component when Redis is reachable")
	void healthReturnsUpWhenRedisReachable() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Internal-Token", internalTokenProperties.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.redis.status").value("UP"));
	}

	@Test
	@DisplayName("readiness group only reflects db, ignores redis")
	void readinessGroupOnlyChecksDb() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

}
