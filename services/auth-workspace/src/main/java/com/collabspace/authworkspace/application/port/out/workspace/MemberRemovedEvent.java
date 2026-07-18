package com.collabspace.authworkspace.application.port.out.workspace;

import java.util.UUID;

public record MemberRemovedEvent(UUID adminId, UUID workspaceId, UUID removedUserId, String correlationId) {
}
