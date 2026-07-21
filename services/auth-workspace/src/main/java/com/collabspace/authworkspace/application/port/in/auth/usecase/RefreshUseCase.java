package com.collabspace.authworkspace.application.port.in.auth.usecase;

import com.collabspace.authworkspace.application.port.in.auth.command.RefreshCommand;
import com.collabspace.authworkspace.application.port.in.auth.result.RefreshResult;

public interface RefreshUseCase {

	RefreshResult refresh(RefreshCommand command);

}
