package com.collabspace.authworkspace.application.port.in.workspace.result;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record ListWorkspacesResult(List<WorkspaceListEntry> workspaces, boolean hasNextPage,
		Optional<Instant> nextAfterCreatedAt, Optional<UUID> nextAfterWorkspaceId) {
}
