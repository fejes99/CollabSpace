package com.collabspace.authworkspace.application.port.in.workspace;

import java.util.Optional;
import java.util.UUID;

public record ChangeMemberRoleCommand(UUID adminId, UUID workspaceId, UUID memberId, String role,
		Optional<String> correlationId, Optional<String> ipAddress) {
}
