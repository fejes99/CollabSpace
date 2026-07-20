package com.collabspace.authworkspace.application.port.in.workspace.result;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record InviteMemberResult(UUID invitedUserId, String email, WorkspaceRole role, UUID workspaceId,
		Instant joinedAt) {
}
