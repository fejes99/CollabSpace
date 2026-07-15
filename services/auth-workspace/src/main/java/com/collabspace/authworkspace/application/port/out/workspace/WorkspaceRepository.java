package com.collabspace.authworkspace.application.port.out.workspace;

import com.collabspace.authworkspace.domain.model.workspace.Workspace;

public interface WorkspaceRepository {

	Workspace save(Workspace workspace);

}
