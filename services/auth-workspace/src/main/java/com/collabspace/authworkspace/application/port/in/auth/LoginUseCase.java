package com.collabspace.authworkspace.application.port.in.auth;

public interface LoginUseCase {

	LoginResult login(LoginCommand command);

}
