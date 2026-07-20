package com.collabspace.authworkspace.adapter.in.rest.workspace.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record WorkspaceListItem(@Schema(description = "Workspace id") UUID id,
		@Schema(description = "Workspace name") String name,
		@Schema(description = "Number of members in this workspace") int memberCount) {
}
