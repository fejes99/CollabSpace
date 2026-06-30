package com.collabspace.authworkspace.adapter.out.persistence.auth;

import com.collabspace.authworkspace.adapter.out.persistence.auth.entity.UserEntity;
import com.collabspace.authworkspace.adapter.out.persistence.auth.repository.UserJpaRepository;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.domain.exception.EmailAlreadyTakenException;
import com.collabspace.authworkspace.domain.model.auth.User;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserJpaAdapter implements UserRepository {

	private final UserJpaRepository jpaRepository;

	public UserJpaAdapter(UserJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public User save(User user) {
		try {
			return toDomain(jpaRepository.save(toEntity(user)));
		}
		catch (DataIntegrityViolationException ex) {
			// users_email_unique is defined in V2__name_email_constraint.sql. If a
			// different constraint fires, rethrow so it surfaces as an unexpected server
			// error.
			if (ex.getCause() instanceof ConstraintViolationException cve
					&& "users_email_unique".equals(cve.getConstraintName())) {
				throw new EmailAlreadyTakenException();
			}
			throw ex;
		}
	}

	private static UserEntity toEntity(User user) {
		return new UserEntity(user.id(), user.name(), user.email(), user.passwordHash().orElse(null));
	}

	private static User toDomain(UserEntity entity) {
		return new User(entity.getId(), entity.getName(), entity.getEmail(),
				Optional.ofNullable(entity.getPasswordHash()), entity.getCreatedAt(), entity.getUpdatedAt());
	}

}
