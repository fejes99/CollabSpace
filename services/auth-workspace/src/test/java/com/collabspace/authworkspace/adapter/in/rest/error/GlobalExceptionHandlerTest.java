package com.collabspace.authworkspace.adapter.in.rest.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.application.port.in.auth.RegisterUseCase;
import com.collabspace.authworkspace.domain.exception.DomainException;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.exception.NotFoundException;
import com.collabspace.authworkspace.support.JwtTestConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@Import(JwtTestConfiguration.class)
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

		@GetMapping(BOOM_PATH)
		String boom() {
			throw new IllegalStateException("internal details that must not leak");
		}

		@GetMapping(CONFLICT_PATH)
		String conflict() {
			throw new EmailAlreadyTakenException();
		}

		@GetMapping(NOT_FOUND_PATH)
		String notFound() {
			throw new NotFoundException("resource not found") {
				@Override
				public URI getType() {
					return DomainException.errorType("test/not-found");
				}
			};
		}

		@GetMapping(DOMAIN_PATH)
		String domain() {
			throw new DomainException("business rule violated") {
				@Override
				public URI getType() {
					return DomainException.errorType("test/domain");
				}
			};
		}

		@PostMapping(VALIDATE_PATH)
		String validate(@RequestBody @Valid ValidatedBody body) {
			return "ok";
		}

		record ValidatedBody(@NotBlank String name) {
		}

	}

	private static final String BOOM_PATH = "/test/boom";

	private static final String CONFLICT_PATH = "/test/conflict";

	private static final String NOT_FOUND_PATH = "/test/not-found";

	private static final String DOMAIN_PATH = "/test/domain";

	private static final String VALIDATE_PATH = "/test/validate";

	private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

	@Autowired
	MockMvc mvc;

	@MockitoBean
	RegisterUseCase registerUseCase;

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
		mvc.perform(get(BOOM_PATH))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/internal-error"))
			.andExpect(jsonPath("$.title").value("Internal server error"))
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
	}

	@Test
	void logsExceptionAtErrorLevel() throws Exception {
		mvc.perform(get(BOOM_PATH)).andReturn();

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.ERROR && e.getMessage().contains("event=unhandled_error"));
	}

	@Test
	void internalExceptionMessageDoesNotLeakToResponse() throws Exception {
		mvc.perform(get(BOOM_PATH))
			.andExpect(content().string(not(containsString("internal details that must not leak"))));
	}

	@Test
	void correlationIdAppearsInErrorLog() throws Exception {
		mvc.perform(get(BOOM_PATH).header("X-Correlation-ID", "trace-123")).andReturn();

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.ERROR && "trace-123".equals(e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	void conflictExceptionReturns409() throws Exception {
		mvc.perform(get(CONFLICT_PATH))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/email-already-taken"))
			.andExpect(jsonPath("$.title").value("Conflict"))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.detail").value("Email address is already registered."));
	}

	@Test
	void notFoundExceptionReturns404() throws Exception {
		mvc.perform(get(NOT_FOUND_PATH))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/test/not-found"))
			.andExpect(jsonPath("$.title").value("Not found"))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("resource not found"));
	}

	@Test
	void domainExceptionReturns422() throws Exception {
		mvc.perform(get(DOMAIN_PATH))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/test/domain"))
			.andExpect(jsonPath("$.title").value("Business rule violation"))
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.detail").value("business rule violated"));
	}

	@Test
	void validationFailureReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(VALIDATE_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-request"))
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mvc.perform(post(VALIDATE_PATH).contentType(MediaType.APPLICATION_JSON).content("{broken json"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/malformed-request"))
			.andExpect(jsonPath("$.title").value("Malformed request"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("The request body could not be parsed."));
	}

}
