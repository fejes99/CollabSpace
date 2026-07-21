package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.adapter.in.rest.auth.request.LoginRequest;
import com.collabspace.authworkspace.adapter.in.rest.auth.request.RegisterRequest;
import com.collabspace.authworkspace.adapter.in.rest.auth.response.LoginResponse;
import com.collabspace.authworkspace.adapter.in.rest.auth.response.RefreshResponse;
import com.collabspace.authworkspace.adapter.in.rest.auth.response.RegisterResponse;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.adapter.in.rest.util.ClientIpResolver;
import com.collabspace.authworkspace.adapter.in.rest.util.CurrentUserIdResolver;
import com.collabspace.authworkspace.application.port.in.auth.command.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.LogoutCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RefreshCommand;
import com.collabspace.authworkspace.application.port.in.auth.command.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.LoginResult;
import com.collabspace.authworkspace.application.port.in.auth.result.RefreshResult;
import com.collabspace.authworkspace.application.port.in.auth.usecase.LoginUseCase;
import com.collabspace.authworkspace.application.port.in.auth.usecase.LogoutUseCase;
import com.collabspace.authworkspace.application.port.in.auth.usecase.RefreshUseCase;
import com.collabspace.authworkspace.application.port.in.auth.usecase.RegisterUseCase;
import com.collabspace.authworkspace.application.service.JwtService;
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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth", description = "User registration and authentication")
public class AuthController {

	private final LoginUseCase loginUseCase;

	private final RegisterUseCase registerUseCase;

	private final boolean cookieSecure;

	private final RefreshUseCase refreshUseCase;

	private final LogoutUseCase logoutUseCase;

	public AuthController(LoginUseCase loginUseCase, RegisterUseCase registerUseCase,
			@Value("${app.cookie.secure:true}") boolean cookieSecure, RefreshUseCase refreshUseCase,
			LogoutUseCase logoutUseCase) {
		this.loginUseCase = loginUseCase;
		this.registerUseCase = registerUseCase;
		this.cookieSecure = cookieSecure;
		this.refreshUseCase = refreshUseCase;
		this.logoutUseCase = logoutUseCase;
	}

	// Scopes down from OpenApiConfig's global requirement -- register fails closed on
	// identity headers, so Swagger's Authorize dialog can't attach them here.
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
		String ipAddress = ClientIpResolver.resolve(httpRequest);

		var command = new RegisterCommand(request.name(), request.email(), request.password(), Optional.of(ipAddress));

		return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse.from(registerUseCase.register(command)));
	}

	@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
	@Operation(summary = "Login user", description = "Login existing user and returns a JWT access and refresh token.")
	@ApiResponse(responseCode = "200", description = "Login successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = LoginResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		String ipAddress = ClientIpResolver.resolve(httpRequest);
		String userAgent = httpRequest.getHeader("User-Agent");

		LoginCommand command = new LoginCommand(request.email(), request.password(), Optional.ofNullable(userAgent),
				Optional.of(ipAddress));

		LoginResult result = loginUseCase.login(command);

		setRefreshTokenCookie(httpResponse, result.refreshToken());

		return ResponseEntity.ok(LoginResponse.from(result));
	}

	@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
	@Operation(summary = "Refresh token",
			description = "Refresh access token for existing user and returns a JWT access and refresh token.")
	@ApiResponse(responseCode = "200", description = "Refresh successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = RefreshResponse.class)))
	@ApiResponse(responseCode = "401", description = "Missing, invalid, or expired refresh token",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(@CookieValue(value = "refresh_token", required = false) String token,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		String ipAddress = ClientIpResolver.resolve(httpRequest);
		String userAgent = httpRequest.getHeader("User-Agent");

		RefreshCommand command = new RefreshCommand(token, Optional.ofNullable(userAgent), Optional.of(ipAddress));

		RefreshResult result = refreshUseCase.refresh(command);

		setRefreshTokenCookie(httpResponse, result.refreshToken());

		return ResponseEntity.ok(RefreshResponse.from(result));
	}

	@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN_SCHEME)
	@Operation(summary = "Logout user",
			description = "Revokes the caller's current access token and deletes their refresh token. A repeat call with the same access token is rejected 401 (that jti is already blocklisted); a repeat call presenting an already-consumed or missing refresh cookie is a no-op 200.")
	@ApiResponse(responseCode = "200", description = "Logout successful")
	@ApiResponse(responseCode = "401", description = "Missing, malformed, or revoked access token",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestHeader(value = "X-JWT-Jti", required = false) String jti,
			@RequestHeader(value = "X-JWT-Iat", required = false) Long iat,
			@CookieValue(value = "refresh_token", required = false) String refreshToken,
			HttpServletResponse httpResponse) {
		// Deliberately not `required = true`: API Gateway forwards X-User-Id and
		// X-JWT-Jti/X-JWT-Iat from the same JWT claims, so all three should always arrive
		// together -- but MembershipStalenessFilter/JwtBlocklistFilter both tolerate a
		// missing jti/iat upstream by design (a claim-mapping regression like ADR-036's
		// should be diagnosable, not a hard filter-level failure). A required
		// @RequestHeader would let Spring's own MissingRequestHeaderException reach the
		// generic 500 handler instead -- this endpoint is the first to bind these headers
		// directly, so there was no existing 401 path to fall back on.
		if (jti == null || iat == null) {
			throw new MalformedIdentityHeadersException("X-JWT-Jti or X-JWT-Iat is missing");
		}

		UUID userId = CurrentUserIdResolver.resolve();

		LogoutCommand command = new LogoutCommand(userId, jti, iat, Optional.ofNullable(refreshToken));

		logoutUseCase.logout(command);
		clearRefreshTokenCookie(httpResponse);

		return ResponseEntity.ok().build();
	}

	private void setRefreshTokenCookie(HttpServletResponse httpResponse, String refreshToken) {
		addRefreshTokenCookie(httpResponse, refreshToken, JwtService.REFRESH_TOKEN_TTL_SECONDS);
	}

	private void clearRefreshTokenCookie(HttpServletResponse httpResponse) {
		addRefreshTokenCookie(httpResponse, "", 0);
	}

	private void addRefreshTokenCookie(HttpServletResponse httpResponse, String value, int maxAgeSeconds) {
		Cookie cookie = new Cookie("refresh_token", value);
		cookie.setAttribute("SameSite", "Strict");
		cookie.setHttpOnly(true);
		cookie.setSecure(cookieSecure);
		cookie.setPath("/v1/auth");
		cookie.setMaxAge(maxAgeSeconds);
		httpResponse.addCookie(cookie);
	}

}
