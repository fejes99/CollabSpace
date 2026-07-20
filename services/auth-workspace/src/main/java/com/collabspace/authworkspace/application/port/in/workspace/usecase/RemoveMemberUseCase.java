package com.collabspace.authworkspace.application.port.in.workspace.usecase;

import com.collabspace.authworkspace.application.port.in.workspace.command.RemoveMemberCommand;

public interface RemoveMemberUseCase {

	void removeMember(RemoveMemberCommand command);

}
