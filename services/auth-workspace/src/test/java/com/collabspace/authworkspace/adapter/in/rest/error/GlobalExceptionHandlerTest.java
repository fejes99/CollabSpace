package com.collabspace.authworkspace.adapter.in.rest.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.collabspace.authworkspace.application.port.in.auth.LoginUseCase;
import com.collabspace.authworkspace.application.port.in.auth.RegisterUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberUseCase;
import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.service.InternalTokenProperties;
import com.collabspace.authworkspace.domain.exception.DomainException;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.exception.InvalidCredentialsException;
import com.collabspace.authworkspace.domain.exception.NotFoundException;
import com.collabspace.authworkspace.support.JwtTestConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@Import({ JwtTestConfiguration.class, ProblemDetailsSecurityHandler.class })
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

	@TestConfiguration
	static class TestControllerConfig {

		@Bean
		ThrowingController throwingController() {
			return new ThrowingController();
		}

		@Bean
		InternalTokenProperties internalTokenProperties() {
			return new InternalTokenProperties("test-internal-token");
		}

		@Bean
		TokenBlocklistRepository tokenBlocklistRepository() {
			return jti -> false;
		}

		@Bean
		MembershipStalenessRepository membershipStalenessRepository() {
			return new MembershipStalenessRepository() {
				@Override
				public void markMembershipChanged(UUID userId, Instant changedAt) {
					// no-op: this test class doesn't exercise membership staleness, just
					// satisfies MembershipStalenessFilter's constructor dependency.
				}

				@Override
				public Optional<Instant> findMembershipChangedAt(UUID userId) {
					return Optional.empty();
				}
			};
		}

	}

	// ThrowingController uses concrete subclasses to trigger each handler:
	// EmailAlreadyTakenException → ConflictException handler
	// InvalidCredentialsException → UnauthorizedException handler
	@RestController
	static class ThrowingController {

		@GetMapping(BOOM_PATH)
		String boom() {
			throw new IllegalStateException("internal details that must not leak");
		}

		@GetMapping(UNAUTHORIZED_PATH)
		String unauthorized() {
			throw new InvalidCredentialsException();
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

		@GetMapping(TYPE_MISMATCH_PATH + "/{id}")
		String typeMismatch(@PathVariable UUID id) {
			return "ok";
		}

		record ValidatedBody(@NotBlank String name) {
		}

	}

	private static final String BOOM_PATH = "/test/boom";

	private static final String UNAUTHORIZED_PATH = "/test/unauthorized";

	private static final String CONFLICT_PATH = "/test/conflict";

	private static final String NOT_FOUND_PATH = "/test/not-found";

	private static final String DOMAIN_PATH = "/test/domain";

	private static final String VALIDATE_PATH = "/test/validate";

	private static final String TYPE_MISMATCH_PATH = "/test/type-mismatch";

	private static final String TEST_USER_ID = "test-user-id";

	private static final String EMPTY_WORKSPACES = "[]";

	private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

	@Autowired
	MockMvc mvc;

	@Autowired
	InternalTokenProperties internalTokenProperties;

	@MockitoBean
	LoginUseCase loginUseCase;

	@MockitoBean
	RegisterUseCase registerUseCase;

	@MockitoBean
	CreateWorkspaceUseCase createWorkspaceUseCase;

	@MockitoBean
	InviteMemberUseCase inviteMemberUseCase;

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
	@DisplayName("unhandled exception returns RFC 9457 problem detail with 500")
	void returnsRfc9457ProblemDetail() throws Exception {
		mvc.perform(get(BOOM_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/internal-error"))
			.andExpect(jsonPath("$.title").value("Internal server error"))
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
			.andExpect(jsonPath("$.instance").value(BOOM_PATH));
	}

	@Test
	@DisplayName("unhandled exception is logged at ERROR level")
	void logsExceptionAtErrorLevel() throws Exception {
		mvc.perform(get(BOOM_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES)).andReturn();

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.ERROR && e.getMessage().contains("event=unhandled_error"));
	}

	@Test
	@DisplayName("internal exception message does not appear in response")
	void internalExceptionMessageDoesNotLeakToResponse() throws Exception {
		mvc.perform(get(BOOM_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(content().string(not(containsString("internal details that must not leak"))));
	}

	@Test
	@DisplayName("correlation ID from request header appears in error log")
	void correlationIdAppearsInErrorLog() throws Exception {
		mvc.perform(get(BOOM_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES)
			.header("X-Correlation-ID", "trace-123")).andReturn();

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.ERROR && "trace-123".equals(e.getMDCPropertyMap().get("correlationId")));
	}

	@Test
	@DisplayName("UnauthorizedException returns 401 with problem detail")
	void unauthorizedExceptionReturns401() throws Exception {
		mvc.perform(get(UNAUTHORIZED_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/invalid-credentials"))
			.andExpect(jsonPath("$.title").value("Unauthorized"))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.detail").value("Invalid credentials."))
			.andExpect(jsonPath("$.instance").value(UNAUTHORIZED_PATH));
	}

	@Test
	@DisplayName("UnauthorizedException is logged at WARN level")
	void unauthorizedExceptionIsLoggedAtWarnLevel() throws Exception {
		mvc.perform(get(UNAUTHORIZED_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES)).andReturn();

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.WARN && e.getMessage().contains("event=unauthorized"));
	}

	@Test
	@DisplayName("ConflictException returns 409 with problem detail")
	void conflictExceptionReturns409() throws Exception {
		mvc.perform(get(CONFLICT_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/auth/email-already-taken"))
			.andExpect(jsonPath("$.title").value("Conflict"))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.detail").value("Email address is already registered."));
	}

	@Test
	@DisplayName("NotFoundException returns 404 with problem detail")
	void notFoundExceptionReturns404() throws Exception {
		mvc.perform(get(NOT_FOUND_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/test/not-found"))
			.andExpect(jsonPath("$.title").value("Not found"))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("resource not found"));
	}

	@Test
	@DisplayName("DomainException returns 422 with problem detail")
	void domainExceptionReturns422() throws Exception {
		mvc.perform(get(DOMAIN_PATH).header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/test/domain"))
			.andExpect(jsonPath("$.title").value("Business rule violation"))
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.detail").value("business rule violated"));
	}

	@Test
	@DisplayName("validation failure returns 400 with errors array")
	void validationFailureReturns400WithErrorsArray() throws Exception {
		mvc.perform(post(VALIDATE_PATH).contentType(MediaType.APPLICATION_JSON)
			.header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES)
			.content("{\"name\": \"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-request"))
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	@DisplayName("malformed JSON body returns 400 with problem detail")
	void malformedJsonReturns400() throws Exception {
		mvc.perform(post(VALIDATE_PATH).contentType(MediaType.APPLICATION_JSON)
			.header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES)
			.content("{broken json"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/malformed-request"))
			.andExpect(jsonPath("$.title").value("Malformed request"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("The request body could not be parsed."));
	}

	@Test
	@DisplayName("path variable type mismatch returns 400 with validation/invalid-path-parameter")
	void pathVariableTypeMismatchReturns400() throws Exception {
		mvc.perform(get(TYPE_MISMATCH_PATH + "/not-a-uuid").header("X-Internal-Token", internalTokenProperties.token())
			.header("X-User-Id", TEST_USER_ID)
			.header("X-User-Workspaces", EMPTY_WORKSPACES))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("https://errors.collabspace.io/validation/invalid-path-parameter"))
			.andExpect(jsonPath("$.title").value("Invalid path parameter"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("Parameter 'id' has an invalid value."));
	}

	// These two verify the fix from earlier this session directly, at the unit level --
	// GlobalExceptionHandler's own @WebMvcTest context here doesn't wire in the real
	// SecurityConfig/ExceptionTranslationFilter, so "did it reach the filter chain" isn't
	// observable through MockMvc in this test class. What's directly, unambiguously
	// verifiable is the one thing that matters: does the handler method rethrow the same
	// exception rather than swallow it, same as GlobalExceptionHandler is package-private
	// and directly instantiable here (same package, no constructor dependencies).

	@Test
	@DisplayName("rethrowAccessDenied re-throws the same AccessDeniedException instead of handling it")
	void rethrowAccessDeniedRethrowsSameException() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		AccessDeniedException original = new AccessDeniedException("denied");

		assertThatThrownBy(() -> handler.rethrowAccessDenied(original)).isSameAs(original);
	}

	@Test
	@DisplayName("rethrowAuthentication re-throws the same AuthenticationException instead of handling it")
	void rethrowAuthenticationRethrowsSameException() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		InsufficientAuthenticationException original = new InsufficientAuthenticationException("auth required");

		assertThatThrownBy(() -> handler.rethrowAuthentication(original)).isSameAs(original);
	}

}
