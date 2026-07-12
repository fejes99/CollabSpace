package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.application.port.in.auth.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.LoginUseCase;
import com.collabspace.authworkspace.application.port.in.auth.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.RegisterUseCase;
import com.collabspace.authworkspace.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth", description = "User registration and authentication")
public class AuthController {

	private final LoginUseCase loginUseCase;

	private final RegisterUseCase registerUseCase;

	private final boolean cookieSecure;

	public AuthController(LoginUseCase loginUseCase, RegisterUseCase registerUseCase,
			@Value("${app.cookie.secure:true}") boolean cookieSecure) {
		this.loginUseCase = loginUseCase;
		this.registerUseCase = registerUseCase;
		this.cookieSecure = cookieSecure;
	}

	// Overrides OpenApiConfig's global security requirement (X-Internal-Token +
	// X-User-Id + X-User-Workspaces) down to just X-Internal-Token -- register is an
	// anonymous route, and HeaderAuthenticationFilter fails closed if identity headers
	// are present here (security-filter.md §3). Without this override, Swagger UI would
	// attach whatever's set in the Authorize dialog for the other two schemes too.
	@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
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
	public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest request,
			HttpServletRequest httpRequest) {
		String forwardedFor = httpRequest.getHeader("X-Forwarded-For");
		String ipAddress = (forwardedFor != null && !forwardedFor.isBlank()) ? forwardedFor.split(",")[0].trim()
				: httpRequest.getRemoteAddr();

		var command = new RegisterCommand(request.name(), request.email(), request.password(), Optional.of(ipAddress));

		return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(registerUseCase.register(command)));
	}

	@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
	@Operation(summary = "Login user", description = "Login existing user and returns a JWT access and refresh token.")
	@ApiResponse(responseCode = "200", description = "Login successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = LoginResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		String forwardedFor = httpRequest.getHeader("X-Forwarded-For");
		String ipAddress = (forwardedFor != null && !forwardedFor.isBlank()) ? forwardedFor.split(",")[0].trim()
				: httpRequest.getRemoteAddr();

		LoginCommand command = new LoginCommand(request.email(), request.password(),
				Optional.ofNullable(httpRequest.getHeader("User-Agent")), Optional.of(ipAddress));

		LoginResult result = loginUseCase.login(command);

		Cookie cookie = new Cookie("refresh_token", result.refreshToken());
		cookie.setAttribute("SameSite", "Strict");
		cookie.setHttpOnly(true);
		cookie.setSecure(cookieSecure);
		cookie.setPath("/auth");
		cookie.setMaxAge(604800);
		httpResponse.addCookie(cookie);

		return ResponseEntity.ok(LoginResponse.from(result));
	}

}
