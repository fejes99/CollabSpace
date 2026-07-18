package com.collabspace.authworkspace.adapter.out.persistence.workspace.repository;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceMembershipEntity;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipJpaRepository extends JpaRepository<WorkspaceMembershipEntity, UUID> {

	Optional<WorkspaceMembershipEntity> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

	List<WorkspaceMembershipEntity> findByWorkspaceId(UUID workspaceId);

	List<WorkspaceMembershipEntity> findByUserId(UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<WorkspaceMembershipEntity> findByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);

}
