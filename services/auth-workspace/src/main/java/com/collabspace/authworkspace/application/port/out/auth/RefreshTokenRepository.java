package com.collabspace.authworkspace.application.port.out.auth;

import com.collabspace.authworkspace.domain.model.auth.RefreshToken;

public interface RefreshTokenRepository {

	RefreshToken save(RefreshToken refreshToken);

}
