package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record InviteMemberResponse(@Schema(description = "Invited member id") UUID invitedUserId,
		@Schema(description = "Invited member email") String email,
		@Schema(description = "Invited member role") String role,
		@Schema(description = "Workspace id") UUID workspaceId,
		@Schema(description = "Membership creation timestamp in UTC",
				example = "2026-07-15T10:00:00Z") Instant joinedAt) {

	public static InviteMemberResponse from(InviteMemberResult result) {
		return new InviteMemberResponse(result.invitedUserId(), result.email(), result.role().getValue(),
				result.workspaceId(), result.joinedAt());
	}

}
