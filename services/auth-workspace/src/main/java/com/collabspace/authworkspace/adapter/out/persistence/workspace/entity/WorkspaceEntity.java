package com.collabspace.authworkspace.adapter.out.persistence.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspaces")
@EntityListeners(AuditingEntityListener.class)
public class WorkspaceEntity {

	@Id
	private UUID id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description", length = 2000)
	private String description;

	@Column(name = "created_by_user_id", nullable = false)
	private UUID createdByUserId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public WorkspaceEntity() {
	}

	public WorkspaceEntity(UUID id, String name, String description, UUID createdByUserId) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.createdByUserId = createdByUserId;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public UUID getCreatedByUserId() {
		return createdByUserId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
