package com.collabspace.authworkspace.application.port.in.auth.usecase;

import com.collabspace.authworkspace.application.port.in.auth.command.LoginCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.LoginResult;

public interface LoginUseCase {

	LoginResult login(LoginCommand command);

}
