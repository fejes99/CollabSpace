package com.collabspace.authworkspace.domain.model.workspace;

import java.time.Instant;
import java.util.UUID;

public record Workspace(UUID id, String name, String description, UUID createdByUserId, Instant createdAt,
		Instant updatedAt) {

}
