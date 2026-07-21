package com.collabspace.authworkspace.adapter.in.rest.auth.response;

import com.collabspace.authworkspace.application.port.in.auth.result.LoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(@Schema(
		description = "RS256-signed JWT access token. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken,
		@Schema(description = "Registered user") UserSummary user) {

	public static LoginResponse from(LoginResult result) {
		var u = result.user();
		return new LoginResponse(result.accessToken(), new UserSummary(u.id(), u.name(), u.email(), u.createdAt()));
	}
}
