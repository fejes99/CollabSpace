package com.collabspace.authworkspace.adapter.out.persistence.workspace;

import com.collabspace.authworkspace.adapter.out.persistence.workspace.entity.WorkspaceEntity;
import com.collabspace.authworkspace.adapter.out.persistence.workspace.repository.WorkspaceJpaRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceJpaAdapter implements WorkspaceRepository {

	private final WorkspaceJpaRepository jpaRepository;

	public WorkspaceJpaAdapter(WorkspaceJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Workspace save(Workspace workspace) {
		return toDomain(jpaRepository.saveAndFlush(toEntity(workspace)));
	}

	private static WorkspaceEntity toEntity(Workspace workspace) {
		return new WorkspaceEntity(workspace.id(), workspace.name(), workspace.description(),
				workspace.createdByUserId());
	}

	private static Workspace toDomain(WorkspaceEntity entity) {
		return new Workspace(entity.getId(), entity.getName(), entity.getDescription(), entity.getCreatedByUserId(),
				entity.getCreatedAt(), entity.getUpdatedAt());
	}

}
