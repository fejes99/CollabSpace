package com.collabspace.authworkspace.adapter.out.persistence.workspace.entity;

import com.collabspace.authworkspace.adapter.out.persistence.AbstractAuditableEntity;
import com.collabspace.authworkspace.adapter.out.persistence.workspace.converter.WorkspaceRoleConverter;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_memberships")
public class WorkspaceMembershipEntity extends AbstractAuditableEntity {

	@OnDelete(action = OnDeleteAction.CASCADE)
	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@OnDelete(action = OnDeleteAction.CASCADE)
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Convert(converter = WorkspaceRoleConverter.class)
	@Column(name = "role", nullable = false)
	private WorkspaceRole role;

	protected WorkspaceMembershipEntity() {
	}

	// createdAt must be threaded through explicitly for the update path (JPA's merge()
	// copies all basic-attribute state from this transient instance onto the managed
	// entity,
	// including a null createdAt, even though @Column(updatable = false) keeps that null
	// out
	// of the generated UPDATE statement -- it doesn't stop merge() from nulling the
	// in-memory
	// field). Harmless on insert: @PrePersist's @CreatedDate unconditionally overwrites
	// it
	// with the auditing clock's value regardless of what's passed here.
	public WorkspaceMembershipEntity(UUID id, UUID workspaceId, UUID userId, WorkspaceRole role, Instant createdAt) {
		super(id);
		this.workspaceId = workspaceId;
		this.userId = userId;
		this.role = role;
		this.createdAt = createdAt;
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

}
