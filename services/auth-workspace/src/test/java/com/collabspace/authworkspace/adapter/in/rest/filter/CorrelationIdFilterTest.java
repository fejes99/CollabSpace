package com.collabspace.authworkspace.adapter.in.rest.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.support.TestContainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "logging.level.com.collabspace.authworkspace=DEBUG")
@Import(TestContainersConfiguration.class)
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

	private final ListAppender<ILoggingEvent> loggingList = new ListAppender<>();

	@Autowired
	MockMvc mvc;

	private Logger rootLogger;

	@BeforeEach
	void setUp() {
		rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		loggingList.start();
		rootLogger.addAppender(loggingList);
	}

	@AfterEach
	void tearDown() {
		rootLogger.detachAppender(loggingList);
		loggingList.stop();
	}

	@Test
	@DisplayName("generates UUID correlation ID when header is absent")
	void requestWithoutHeader() throws Exception {
		var result = mvc.perform(get("/actuator/health")).andExpect(header().exists("X-Correlation-ID")).andReturn();

		String correlationId = result.getResponse().getHeader("X-Correlation-ID");

		assertThat(correlationId).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
		assertThat(loggingList.list)
			.anyMatch(e -> Objects.equals(correlationId, e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	@DisplayName("echoes provided correlation ID header")
	void requestWithHeader() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Correlation-ID", "test-correlation-id"))
			.andExpect(header().string("X-Correlation-ID", "test-correlation-id"));

		assertThat(loggingList.list)
			.anyMatch(e -> "test-correlation-id".equals(e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	@DisplayName("generates UUID correlation ID when header is blank")
	void requestWithEmptyHeader() throws Exception {
		var result = mvc.perform(get("/actuator/health").header("X-Correlation-ID", ""))
			.andExpect(header().exists("X-Correlation-ID"))
			.andReturn();

		String correlationId = result.getResponse().getHeader("X-Correlation-ID");

		assertThat(correlationId).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
		assertThat(loggingList.list)
			.anyMatch(e -> Objects.equals(correlationId, e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	@DisplayName("truncates oversized correlation ID to 64 characters")
	void requestWithOversizedHeader() throws Exception {
		String oversizedId = "a".repeat(100);
		String expectedId = oversizedId.substring(0, 64);

		mvc.perform(get("/actuator/health").header("X-Correlation-ID", oversizedId))
			.andExpect(header().string("X-Correlation-ID", expectedId));

		assertThat(loggingList.list).anyMatch(e -> expectedId.equals(e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	@DisplayName("correlation ID does not leak from one request to the next")
	void correlationIdDoesNotLeakBetweenRequests() throws Exception {
		mvc.perform(get("/actuator/health").header("X-Correlation-ID", "first-id"));

		String secondId = mvc.perform(get("/actuator/health"))
			.andExpect(header().exists("X-Correlation-ID"))
			.andReturn()
			.getResponse()
			.getHeader("X-Correlation-ID");

		assertThat(secondId).isNotEqualTo("first-id")
			.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
	}

}
