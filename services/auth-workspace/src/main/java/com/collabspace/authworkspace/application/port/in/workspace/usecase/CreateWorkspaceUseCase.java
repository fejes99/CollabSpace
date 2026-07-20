package com.collabspace.authworkspace.application.port.in.workspace.usecase;

import com.collabspace.authworkspace.application.port.in.workspace.command.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.result.CreateWorkspaceResult;

public interface CreateWorkspaceUseCase {

	CreateWorkspaceResult create(CreateWorkspaceCommand command);

}
