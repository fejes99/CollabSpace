package com.collabspace.authworkspace.application.port.in.auth.usecase;

import com.collabspace.authworkspace.application.port.in.auth.command.LogoutCommand;

public interface LogoutUseCase {

	void logout(LogoutCommand command);

}
