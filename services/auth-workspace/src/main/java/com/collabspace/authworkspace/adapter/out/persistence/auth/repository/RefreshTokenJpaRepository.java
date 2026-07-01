package com.collabspace.authworkspace.adapter.out.persistence.auth.repository;

import com.collabspace.authworkspace.adapter.out.persistence.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

}
