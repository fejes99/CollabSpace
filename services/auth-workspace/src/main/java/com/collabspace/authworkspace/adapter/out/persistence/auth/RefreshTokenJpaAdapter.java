package com.collabspace.authworkspace.adapter.out.persistence.auth;

import com.collabspace.authworkspace.adapter.out.persistence.auth.entity.RefreshTokenEntity;
import com.collabspace.authworkspace.adapter.out.persistence.auth.repository.RefreshTokenJpaRepository;
import com.collabspace.authworkspace.application.port.out.auth.RefreshTokenRepository;
import com.collabspace.authworkspace.domain.model.auth.RefreshToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RefreshTokenJpaAdapter implements RefreshTokenRepository {

	private final RefreshTokenJpaRepository jpaRepository;

	public RefreshTokenJpaAdapter(RefreshTokenJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public RefreshToken save(RefreshToken refreshToken) {
		return toDomain(jpaRepository.save(toEntity(refreshToken)));
	}

	private static RefreshTokenEntity toEntity(RefreshToken refreshToken) {
		return new RefreshTokenEntity(refreshToken.id(), refreshToken.userId(), refreshToken.tokenHash(),
				refreshToken.createdAt(), refreshToken.expiresAt(), refreshToken.userAgent().orElse(null),
				refreshToken.ipAddress().orElse(null));
	}

	private static RefreshToken toDomain(RefreshTokenEntity entity) {
		return new RefreshToken(entity.getId(), entity.getUserId(), entity.getTokenHash(), entity.getCreatedAt(),
				entity.getExpiresAt(), Optional.ofNullable(entity.getUserAgent()),
				Optional.ofNullable(entity.getIpAddress()));
	}

}
