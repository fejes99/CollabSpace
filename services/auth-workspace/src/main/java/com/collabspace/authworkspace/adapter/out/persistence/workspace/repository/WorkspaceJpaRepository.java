package com.collabspace.authworkspace.adapter.out.persistence.workspace.repository;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceEntity;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceListRow;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {

	@Query("""
			SELECT new com.collabspace.authworkspace.application.port.out.workspace.WorkspaceListRow(
			    w.id, w.name, w.createdAt,
			    (SELECT COUNT(m) FROM WorkspaceMembershipEntity m WHERE m.workspaceId = w.id)
			)
			FROM WorkspaceEntity w
			WHERE (w.createdAt > :afterCreatedAt
			       OR (w.createdAt = :afterCreatedAt AND w.id > :afterWorkspaceId))
			ORDER BY w.createdAt ASC, w.id ASC
			""")
	List<WorkspaceListRow> findPage(Instant afterCreatedAt, UUID afterWorkspaceId, Limit limit);

}
