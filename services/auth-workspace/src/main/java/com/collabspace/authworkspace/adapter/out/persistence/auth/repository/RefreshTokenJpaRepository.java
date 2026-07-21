package com.collabspace.authworkspace.adapter.out.persistence.auth.repository;

import com.collabspace.authworkspace.adapter.out.persistence.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

	Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

	@Modifying
	@Transactional
	@Query("DELETE FROM RefreshTokenEntity rt WHERE rt.id = :id")
	int deleteByIdReturningCount(UUID id);

}
