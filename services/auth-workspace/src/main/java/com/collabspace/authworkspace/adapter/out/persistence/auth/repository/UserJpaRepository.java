package com.collabspace.authworkspace.adapter.out.persistence.auth.repository;

import com.collabspace.authworkspace.adapter.out.persistence.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

	Optional<UserEntity> findByEmail(String email);

}
