package com.collabspace.authworkspace.adapter.out.persistence.workspace.repository;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {

}
