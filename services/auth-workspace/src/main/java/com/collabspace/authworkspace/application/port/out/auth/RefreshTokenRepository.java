package com.collabspace.authworkspace.application.port.out.auth;

import com.collabspace.authworkspace.domain.model.auth.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	RefreshToken save(RefreshToken refreshToken);

	int deleteByIdReturningCount(UUID id);

}
