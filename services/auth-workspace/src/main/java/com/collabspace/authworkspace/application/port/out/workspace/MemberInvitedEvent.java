package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;

import java.util.UUID;

public record MemberInvitedEvent(UUID adminId, UUID workspaceId, UUID invitedUserId, String email, WorkspaceRole role,
		String correlationId) {
}
