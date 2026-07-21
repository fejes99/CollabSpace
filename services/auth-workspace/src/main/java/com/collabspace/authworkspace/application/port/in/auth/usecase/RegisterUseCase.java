package com.collabspace.authworkspace.application.port.in.auth.usecase;

import com.collabspace.authworkspace.application.port.in.auth.command.RegisterCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.RegisterResult;

public interface RegisterUseCase {

	RegisterResult register(RegisterCommand command);

}
