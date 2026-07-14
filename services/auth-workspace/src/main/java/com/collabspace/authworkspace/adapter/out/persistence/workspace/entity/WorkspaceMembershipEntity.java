package com.collabspace.authworkspace.adapter.out.persistence.workspace.entity;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.converter.WorkspaceRoleConverter;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_memberships")
@EntityListeners(AuditingEntityListener.class)
public class WorkspaceMembershipEntity {

	@Id
	private UUID id;

	@OnDelete(action = OnDeleteAction.CASCADE)
	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@OnDelete(action = OnDeleteAction.CASCADE)
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Convert(converter = WorkspaceRoleConverter.class)
	@Column(name = "role", nullable = false)
	private WorkspaceRole role;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public WorkspaceMembershipEntity(UUID id, UUID workspaceId, UUID userId, WorkspaceRole role) {
		this.id = id;
		this.workspaceId = workspaceId;
		this.userId = userId;
		this.role = role;
	}

	public WorkspaceMembershipEntity() {
	}

	public UUID getId() {
		return id;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public UUID getUserId() {
		return userId;
	}

	public WorkspaceRole getRole() {
		return role;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
