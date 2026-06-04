package com.collabspace.authworkspace.adapter.in.rest.auth;

import com.collabspace.authworkspace.application.port.in.auth.RegisterResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(@Schema(
		description = "RS256-signed JWT access token. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken,
		@Schema(description = "Registered user") UserSummary user) {

	public record UserSummary(@Schema(description = "User ID") UUID id,
			@Schema(description = "Display name") String name,
			@Schema(description = "Normalised (lowercased) email address") String email,
			@Schema(description = "Registration timestamp in UTC",
					example = "2026-06-02T10:00:00Z") Instant createdAt) {
	}

	public static RegisterResponse from(RegisterResult result) {
		var u = result.user();
		return new RegisterResponse(result.accessToken(), new UserSummary(u.id(), u.name(), u.email(), u.createdAt()));
	}

}
