package com.collabspace.authworkspace.adapter.out.persistence.auth.entity;

import com.collabspace.authworkspace.adapter.out.persistence.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity extends AbstractAuditableEntity {

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "email", nullable = false, unique = true, length = 320)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	protected UserEntity() {
	}

	public UserEntity(UUID id, String name, String email, String passwordHash) {
		super(id);
		this.name = name;
		this.email = email;
		this.passwordHash = passwordHash;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

}
