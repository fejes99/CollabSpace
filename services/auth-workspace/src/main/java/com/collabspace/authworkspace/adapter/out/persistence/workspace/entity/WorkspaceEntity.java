package com.collabspace.authworkspace.adapter.out.persistence.workspace.entity;

import com.collabspace.authworkspace.adapter.out.persistence.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "workspaces")
public class WorkspaceEntity extends AbstractAuditableEntity {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description", length = 2000)
	private String description;

	@Column(name = "created_by_user_id", nullable = false)
	private UUID createdByUserId;

	protected WorkspaceEntity() {
	}

	public WorkspaceEntity(UUID id, String name, String description, UUID createdByUserId) {
		super(id);
		this.name = name;
		this.description = description;
		this.createdByUserId = createdByUserId;
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

}
