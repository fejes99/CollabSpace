package com.collabspace.authworkspace.application.port.in.workspace.usecase;

import com.collabspace.authworkspace.application.port.in.workspace.command.ListWorkspacesCommand;
import com.collabspace.authworkspace.application.port.in.workspace.result.ListWorkspacesResult;

public interface ListWorkspacesUseCase {

	ListWorkspacesResult list(ListWorkspacesCommand command);

}
