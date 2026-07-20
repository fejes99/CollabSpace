package com.collabspace.authworkspace.application.port.in.workspace.command;

import java.util.Optional;
import java.util.UUID;

public record RemoveMemberCommand(UUID adminId, UUID workspaceId, UUID memberId, Optional<String> correlationId,
		Optional<String> ipAddress) {
}
