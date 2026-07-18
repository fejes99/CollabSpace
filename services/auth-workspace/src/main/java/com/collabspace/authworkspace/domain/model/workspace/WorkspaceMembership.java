package com.collabspace.authworkspace.domain.model.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMembership(UUID id, UUID workspaceId, UUID userId, WorkspaceRole role, Instant createdAt,
		Instant updatedAt) {

	public WorkspaceMembership changeRole(WorkspaceRole newRole) {
		return new WorkspaceMembership(this.id, this.workspaceId, this.userId, newRole, this.createdAt, this.updatedAt);
	}
}
