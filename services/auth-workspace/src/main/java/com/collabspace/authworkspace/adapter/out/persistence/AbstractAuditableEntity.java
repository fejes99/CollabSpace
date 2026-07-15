package com.collabspace.authworkspace.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

// Shared by entities whose table has a client-generated UUID id plus created_at/updated_at
// columns populated by JPA auditing. RefreshTokenEntity doesn't extend this -- its table has
// no updated_at column, and its createdAt is explicitly constructor-supplied, not left to
// the auditing listener, so it isn't the same pattern despite the surface resemblance.
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractAuditableEntity {

	@Id
	protected UUID id;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	protected Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	protected Instant updatedAt;

	protected AbstractAuditableEntity() {
	}

	protected AbstractAuditableEntity(UUID id) {
		this.id = id;
	}

	public UUID getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
