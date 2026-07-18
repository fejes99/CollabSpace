package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;

import java.util.UUID;

public record MemberRoleChangedEvent(UUID adminId, UUID workspaceId, UUID memberId, WorkspaceRole previousRole,
		WorkspaceRole newRole, String correlationId) {
}
