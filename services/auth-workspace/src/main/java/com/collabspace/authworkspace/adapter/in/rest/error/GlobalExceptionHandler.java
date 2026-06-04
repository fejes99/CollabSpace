package com.collabspace.authworkspace.adapter.in.rest.error;

import com.collabspace.authworkspace.domain.exception.ConflictException;
import com.collabspace.authworkspace.domain.exception.DomainException;
import com.collabspace.authworkspace.domain.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
		log.warn("event=validation_failed fields={}", fieldNames(ex));
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

}
