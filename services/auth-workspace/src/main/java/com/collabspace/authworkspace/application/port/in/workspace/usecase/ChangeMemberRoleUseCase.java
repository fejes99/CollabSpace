package com.collabspace.authworkspace.application.port.in.workspace.usecase;

import com.collabspace.authworkspace.application.port.in.workspace.command.ChangeMemberRoleCommand;
import com.collabspace.authworkspace.application.port.in.workspace.result.ChangeMemberRoleResult;

public interface ChangeMemberRoleUseCase {

	ChangeMemberRoleResult changeMemberRole(ChangeMemberRoleCommand command);

}
