package com.collabspace.authworkspace.application.port.in.workspace.result;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ChangeMemberRoleResult(UUID workspaceId, UUID userId, WorkspaceRole role, Instant updatedAt,
		Optional<String> accessToken) {
}
