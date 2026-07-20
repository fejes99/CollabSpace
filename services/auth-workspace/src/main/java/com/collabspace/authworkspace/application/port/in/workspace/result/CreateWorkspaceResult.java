package com.collabspace.authworkspace.application.port.in.workspace.result;

import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;

public record CreateWorkspaceResult(Workspace workspace, WorkspaceRole role, String accessToken) {
}
