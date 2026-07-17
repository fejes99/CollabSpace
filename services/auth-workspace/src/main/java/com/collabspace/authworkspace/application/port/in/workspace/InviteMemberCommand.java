package com.collabspace.authworkspace.application.port.in.workspace;

import java.util.Optional;
import java.util.UUID;

public record InviteMemberCommand(UUID adminId, UUID workspaceId, String email, Optional<String> role,
		Optional<String> correlationId, Optional<String> ipAddress) {
}
