package com.collabspace.authworkspace.application.port.in.workspace.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ListWorkspacesCommand(UUID userId, int limit, Optional<Instant> afterCreatedAt,
		Optional<UUID> afterWorkspaceId, Optional<String> correlationId) {
}
