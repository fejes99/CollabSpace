package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipRepository {

	WorkspaceMembership save(WorkspaceMembership workspaceMembership);

	Optional<WorkspaceMembership> findById(UUID workspaceMembershipId);

	List<WorkspaceMembership> findByWorkspaceId(UUID workspaceId);

	List<WorkspaceMembership> findByUserId(UUID userId);

}
