package com.collabspace.authworkspace.application.port.in.workspace.usecase;

import com.collabspace.authworkspace.application.port.in.workspace.command.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.result.InviteMemberResult;

public interface InviteMemberUseCase {

	InviteMemberResult invite(InviteMemberCommand command);

}
