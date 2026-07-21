package com.collabspace.authworkspace.adapter.in.rest.auth.response;

import com.collabspace.authworkspace.application.port.in.auth.result.RefreshResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefreshResponse(@Schema(
		description = "RS256-signed JWT access token. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken) {
	public static RefreshResponse from(RefreshResult result) {
		return new RefreshResponse(result.accessToken());
	}
}
