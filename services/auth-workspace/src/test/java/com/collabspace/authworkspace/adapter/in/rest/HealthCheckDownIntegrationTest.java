package com.collabspace.authworkspace.adapter.in.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = { "spring.datasource.url=jdbc:postgresql://localhost:1/invalid",
				"spring.datasource.hikari.initialization-fail-timeout=0",
				"spring.datasource.hikari.connection-timeout=500" })
@AutoConfigureMockMvc
class HealthCheckDownIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void healthReturnsServiceUnavailableWhenDbDown() throws Exception {
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.components.db.status").value("DOWN"));
	}

}
