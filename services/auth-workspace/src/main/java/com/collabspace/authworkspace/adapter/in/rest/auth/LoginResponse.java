package com.collabspace.authworkspace.adapter.in.rest.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(@Schema(
		description = "RS256-signed JWT access token. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken,
		@Schema(description = "Registered user") UserSummary user) {
}
