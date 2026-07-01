package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.application.port.in.auth.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.RegisterUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth", description = "User registration and authentication")
public class AuthController {

	private final RegisterUseCase registerUseCase;

	public AuthController(RegisterUseCase registerUseCase) {
		this.registerUseCase = registerUseCase;
	}

	@Operation(summary = "Register a new user",
			description = "Creates a user account and returns a JWT access token. The user is logged in immediately after registration.")
	@ApiResponse(responseCode = "201", description = "Registration successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = RegisterResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Email already registered",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/register")
	public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
		var command = new RegisterCommand(request.name(), request.email(), request.password());
		return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(registerUseCase.register(command)));
	}

	@Operation(summary = "Login user", description = "Login existing user and returns a JWT access and refresh token.")
	@ApiResponse(responseCode = "200", description = "Login successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = LoginResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request,
			HttpServletRequest httpRequest) {
		// Extract ip and user agent

		// Create command

		// Set-Cookie refresh token in response Header as HttpOnly, Secure and Strict.
		// Include token exp time

		throw new UnsupportedOperationException();
	}

}
