package com.collabspace.authworkspace.adapter.out.persistence.workspace;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceMembershipEntity;
import com.collabspace.authworkspace.adapter.out.persistence.workspace.repository.WorkspaceMembershipJpaRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkspaceMembershipJpaAdapter implements WorkspaceMembershipRepository {

	private final WorkspaceMembershipJpaRepository jpaRepository;

	public WorkspaceMembershipJpaAdapter(WorkspaceMembershipJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public WorkspaceMembership save(WorkspaceMembership workspaceMembership) {
		return toDomain(jpaRepository.saveAndFlush(toEntity(workspaceMembership)));
	}

	@Override
	public Optional<WorkspaceMembership> findById(UUID id) {
		return jpaRepository.findById(id).map(WorkspaceMembershipJpaAdapter::toDomain);
	}

	@Override
	public List<WorkspaceMembership> findByWorkspaceId(UUID workspaceId) {
		return jpaRepository.findByWorkspaceId(workspaceId)
			.stream()
			.map(WorkspaceMembershipJpaAdapter::toDomain)
			.toList();
	}

	@Override
	public List<WorkspaceMembership> findByUserId(UUID userId) {
		return jpaRepository.findByUserId(userId).stream().map(WorkspaceMembershipJpaAdapter::toDomain).toList();
	}

	private static WorkspaceMembershipEntity toEntity(WorkspaceMembership workspaceMembership) {
		return new WorkspaceMembershipEntity(workspaceMembership.id(), workspaceMembership.workspaceId(),
				workspaceMembership.userId(), workspaceMembership.role());
	}

	private static WorkspaceMembership toDomain(WorkspaceMembershipEntity entity) {
		return new WorkspaceMembership(entity.getId(), entity.getWorkspaceId(), entity.getUserId(), entity.getRole(),
				entity.getCreatedAt(), entity.getUpdatedAt());
	}

}
