package com.collabspace.authworkspace.adapter.in.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
class GlobalExceptionHandlerTest {

	@TestConfiguration
	static class TestControllerConfig {

		@Bean
		ThrowingController throwingController() {
			return new ThrowingController();
		}

	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/boom")
		String boom() {
			throw new RuntimeException("internal details that must not leak");
		}

	}

	private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void attachLogger() {
		Logger logger = (Logger) LoggerFactory.getLogger("com.collabspace.authworkspace");
		logCapture.start();
		logger.addAppender(logCapture);
	}

	@AfterEach
	void detachLogger() {
		Logger logger = (Logger) LoggerFactory.getLogger("com.collabspace.authworkspace");
		logger.detachAppender(logCapture);
		logCapture.stop();
	}

	@Test
	void returnsRfc9457ProblemDetail() throws Exception {
		mvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.title").value("Internal Server Error"))
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
	}

	@Test
	void logsExceptionAtErrorLevel() throws Exception {
		mvc.perform(get("/test/boom")).andReturn();

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.ERROR && e.getMessage().contains("Unhandled exception"));
	}

	@Test
	void internalExceptionMessageDoesNotLeakToResponse() throws Exception {
		mvc.perform(get("/test/boom"))
			.andExpect(content().string(not(containsString("internal details that must not leak"))));
	}

	@Test
	void correlationIdAppearsInErrorLog() throws Exception {
		mvc.perform(get("/test/boom").header("X-Correlation-ID", "trace-123")).andReturn();

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.ERROR && "trace-123".equals(e.getMDCPropertyMap().get("correlationId")));
	}

}
