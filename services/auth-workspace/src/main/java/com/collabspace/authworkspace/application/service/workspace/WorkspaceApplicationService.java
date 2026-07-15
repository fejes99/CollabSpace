package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceApplicationService implements CreateWorkspaceUseCase {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceApplicationService.class);

	private final WorkspaceRepository workspaceRepository;

	private final WorkspaceMembershipRepository workspaceMembershipRepository;

	private final JwtService jwtService;

	private final Clock clock;

	private final CommitThenAction commitThenAction;

	public WorkspaceApplicationService(Clock clock, WorkspaceRepository workspaceRepository,
			WorkspaceMembershipRepository workspaceMembershipRepository, JwtService jwtService,
			CommitThenAction commitThenAction) {
		this.clock = clock;
		this.workspaceRepository = workspaceRepository;
		this.workspaceMembershipRepository = workspaceMembershipRepository;
		this.jwtService = jwtService;
		this.commitThenAction = commitThenAction;
	}

	@Override
	public CreateWorkspaceResult create(CreateWorkspaceCommand command) {
		String normalisedName = command.name().trim();
		Instant now = clock.instant();

		Workspace workspace = new Workspace(UUID.randomUUID(), normalisedName, command.description(), command.userId(),
				now, now);
		WorkspaceMembership workspaceMembership = new WorkspaceMembership(UUID.randomUUID(), workspace.id(),
				command.userId(), WorkspaceRole.ADMIN, now, now);

		AccessToken accessToken = commitThenAction.run(() -> {
			workspaceRepository.save(workspace);
			workspaceMembershipRepository.save(workspaceMembership);
		}, () -> {
			List<WorkspaceMembership> userWorkspaces = workspaceMembershipRepository.findByUserId(command.userId());
			return jwtService.issueAccessToken(command.userId().toString(), userWorkspaces);
		});

		log.info("event=workspace_created userId={} workspaceId={} name={} ip={} jti={}", command.userId(),
				workspace.id(), workspace.name(), command.ipAddress().orElse(null), accessToken.jti());

		return new CreateWorkspaceResult(workspace, workspaceMembership.role(), accessToken.token());
	}

}
