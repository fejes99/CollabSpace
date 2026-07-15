package com.collabspace.authworkspace.adapter.out.persistence.workspace.repository;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkspaceMembershipJpaRepository extends JpaRepository<WorkspaceMembershipEntity, UUID> {

	List<WorkspaceMembershipEntity> findByWorkspaceId(UUID workspaceId);

	List<WorkspaceMembershipEntity> findByUserId(UUID userId);

}
