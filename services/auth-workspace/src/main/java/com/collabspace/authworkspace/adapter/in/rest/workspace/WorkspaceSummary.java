package com.collabspace.authworkspace.adapter.in.rest.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceSummary(@Schema(description = "Id") UUID id, @Schema(description = "Workspace name") String name,
		@Schema(description = "Workspace description") String description,
		@Schema(description = "User created workspace") UUID createdByUserId,
		@Schema(description = "Workspace creation timestamp in UTC",
				example = "2026-06-02T10:00:00Z") Instant createdAt,
		@Schema(description = "Workspace creation timestamp in UTC",
				example = "2026-06-02T10:00:00Z") Instant updatedAt) {
}
