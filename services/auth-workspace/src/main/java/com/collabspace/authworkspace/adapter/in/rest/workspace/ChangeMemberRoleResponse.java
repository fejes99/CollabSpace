package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record ChangeMemberRoleResponse(@Schema(description = "Workspace id") UUID workspaceId,
		@Schema(description = "Changed member id") UUID userId,
		@Schema(description = "Changed member role") String role,
		@Schema(description = "Membership change timestamp in UTC", example = "2026-07-15T10:00:00Z") Instant updatedAt,
		@Schema(description = "RS256-signed JWT access token for self change role. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken) {

	public static ChangeMemberRoleResponse from(ChangeMemberRoleResult result) {
		return new ChangeMemberRoleResponse(result.workspaceId(), result.userId(), result.role().getValue(),
				result.updatedAt(), result.accessToken().orElse(null));
	}
}
