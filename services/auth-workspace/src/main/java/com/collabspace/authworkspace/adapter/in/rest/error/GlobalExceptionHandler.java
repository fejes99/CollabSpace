package com.collabspace.authworkspace.adapter.in.rest.error;

import com.collabspace.authworkspace.adapter.in.rest.workspace.validation.ValidAfter;
import com.collabspace.authworkspace.domain.exception.ConflictException;
import com.collabspace.authworkspace.domain.exception.DomainException;
import com.collabspace.authworkspace.domain.exception.NotFoundException;
import com.collabspace.authworkspace.domain.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream().map(e -> {
			String msg = e.getDefaultMessage() != null ? e.getDefaultMessage() : "must be valid";
			return Map.of("field", e.getField(), "message", msg);
		}).toList();

		ProblemDetail problem = ProblemDetail.forStatus(400);
		problem.setType(DomainException.errorType("validation/invalid-request"));
		problem.setTitle("Validation failed");
		problem.setDetail("The request body contains invalid fields.");
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("errors", errors);
		log.warn("event=validation_failed uri={} fields={}", request.getRequestURI(), fieldNames(ex));
		return problem;
	}

	// Thrown for @RequestParam/@PathVariable constraint annotations (@Min, @Max,
	// @ValidAfter) on a @Validated-annotated controller -- a different exception type
	// from MethodArgumentNotValidException, which only covers @Valid @RequestBody.
	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		// Checked by constraint annotation type, not by property-path string matching --
		// a string suffix like ".after" would also misfire on any future @RequestParam
		// that happens to share that name for an unrelated reason.
		boolean cursorInvalid = ex.getConstraintViolations()
			.stream()
			.anyMatch(v -> v.getConstraintDescriptor().getAnnotation() instanceof ValidAfter);

		// Every violation is reported, even when limit and after are both invalid in the
		// same request -- picking one type/title to headline (cursor takes priority,
		// since it's the more specific error) must not cause the other violation to go
		// unmentioned in the response body.
		List<Map<String, String>> errors = ex.getConstraintViolations().stream().map(v -> {
			String path = v.getPropertyPath().toString();
			String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
			return Map.of("field", field, "message", v.getMessage());
		}).toList();

		ProblemDetail problem = ProblemDetail.forStatus(400);
		if (cursorInvalid) {
			problem.setType(DomainException.errorType("validation/invalid-cursor"));
			problem.setTitle("Invalid cursor");
			problem.setDetail("The 'after' cursor is malformed or invalid.");
		}
		else {
			problem.setType(DomainException.errorType("validation/invalid-request"));
			problem.setTitle("Validation failed");
			problem.setDetail("The request contains invalid parameters.");
		}
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("errors", errors);
		log.warn("event=constraint_violation uri={} violations={}", request.getRequestURI(), violationSummary(ex));
		return problem;
	}

	@ExceptionHandler(UnauthorizedException.class)
	ProblemDetail handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(401);
		problem.setType(ex.getType());
		problem.setTitle("Unauthorized");
		problem.setDetail(ex.getMessage());
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=unauthorized type={}", ex.getType());
		return problem;
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(409);
		problem.setType(ex.getType());
		problem.setTitle("Conflict");
		problem.setDetail(ex.getMessage());
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=conflict type={}", ex.getType());
		return problem;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(404);
		problem.setType(ex.getType());
		problem.setTitle("Not found");
		problem.setDetail(ex.getMessage());
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=not_found type={}", ex.getType());
		return problem;
	}

	@ExceptionHandler(DomainException.class)
	ProblemDetail handleDomain(DomainException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(422);
		problem.setType(ex.getType());
		problem.setTitle("Business rule violation");
		problem.setDetail(ex.getMessage());
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=domain_exception type={}", ex.getType());
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(400);
		problem.setType(DomainException.errorType("validation/malformed-request"));
		problem.setTitle("Malformed request");
		problem.setDetail("The request body could not be parsed.");
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=malformed_request uri={}", request.getRequestURI());
		return problem;
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		boolean isQueryParam = ex.getParameter().hasParameterAnnotation(RequestParam.class);

		ProblemDetail problem = ProblemDetail.forStatus(400);
		if (isQueryParam) {
			problem.setType(DomainException.errorType("validation/invalid-request"));
			problem.setTitle("Validation failed");
			problem.setDetail("Parameter '" + ex.getName() + "' has an invalid value.");
			problem.setProperty("errors", List.of(Map.of("field", ex.getName(), "message", "has an invalid value")));
		}
		else {
			problem.setType(DomainException.errorType("validation/invalid-path-parameter"));
			problem.setTitle("Invalid path parameter");
			problem.setDetail("Parameter '" + ex.getName() + "' has an invalid value.");
		}
		problem.setInstance(URI.create(request.getRequestURI()));
		log.warn("event=invalid_parameter uri={} parameter={} source={}", request.getRequestURI(), ex.getName(),
				isQueryParam ? "query" : "path");
		return problem;
	}

	/**
	 * @PreAuthorize-thrown AccessDeniedException/AuthenticationException are raised
	 * inside DispatcherServlet's own handler invocation, so this @RestControllerAdvice's
	 * resolvers see them first -- before they'd ever reach ExceptionTranslationFilter, a
	 * servlet filter wrapping the *outside* of DispatcherServlet. Without these two
	 * handlers, the catch-all below swallows them as a generic 500 instead of letting
	 * ProblemDetailsSecurityHandler render the correct 401/403 body. Re-throwing here
	 * lets them propagate out of DispatcherServlet to the filter chain where they belong.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	void rethrowAccessDenied(AccessDeniedException ex) {
		throw ex;
	}

	@ExceptionHandler(AuthenticationException.class)
	void rethrowAuthentication(AuthenticationException ex) {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("event=unhandled_error", ex);
		ProblemDetail problem = ProblemDetail.forStatus(500);
		problem.setType(DomainException.errorType("internal-error"));
		problem.setTitle("Internal server error");
		problem.setDetail("An unexpected error occurred.");
		problem.setInstance(URI.create(request.getRequestURI()));
		return problem;
	}

	private static String fieldNames(MethodArgumentNotValidException ex) {
		return ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(FieldError::getField)
			.distinct()
			.reduce((a, b) -> a + "," + b)
			.orElse("unknown");
	}

	private static String violationSummary(ConstraintViolationException ex) {
		return ex.getConstraintViolations()
			.stream()
			.map(v -> v.getPropertyPath() + "=" + v.getMessage())
			.reduce((a, b) -> a + "," + b)
			.orElse("unknown");
	}

}
