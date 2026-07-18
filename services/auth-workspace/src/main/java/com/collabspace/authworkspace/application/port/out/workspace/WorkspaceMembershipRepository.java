package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipRepository {

	Optional<WorkspaceMembership> findById(UUID workspaceMembershipId);

	Optional<WorkspaceMembership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

	List<WorkspaceMembership> findByWorkspaceId(UUID workspaceId);

	List<WorkspaceMembership> findByUserId(UUID userId);

	WorkspaceMembership save(WorkspaceMembership workspaceMembership);

	int countAdminsForUpdate(UUID workspaceId);

}
