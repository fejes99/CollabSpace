package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateWorkspaceResponse(@Schema(
		description = "RS256-signed JWT access token. Include as Authorization: Bearer <token> on authenticated requests.") String accessToken,
		@Schema(description = "Created workspace") WorkspaceSummary workspace,
		@Schema(description = "User role") String role) {

	public static CreateWorkspaceResponse from(CreateWorkspaceResult result) {
		var workspace = result.workspace();
		return new CreateWorkspaceResponse(result.accessToken(),
				new WorkspaceSummary(workspace.id(), workspace.name(), workspace.description(),
						workspace.createdByUserId(), workspace.createdAt(), workspace.updatedAt()),
				result.role().getValue());
	}
}
