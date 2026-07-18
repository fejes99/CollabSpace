package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.Workspace;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {

	Optional<Workspace> findById(UUID workspaceId);

	Workspace save(Workspace workspace);

}
