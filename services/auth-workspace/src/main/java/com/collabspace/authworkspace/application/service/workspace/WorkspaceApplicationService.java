package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.JwtService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class WorkspaceApplicationService implements CreateWorkspaceUseCase {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceApplicationService.class);

	private final WorkspaceRepository workspaceRepository;

	private final WorkspaceMembershipRepository workspaceMembershipRepository;

	private final JwtService jwtService;

	private final Clock clock;

	public WorkspaceApplicationService(Clock clock, WorkspaceRepository workspaceRepository,
			WorkspaceMembershipRepository workspaceMembershipRepository, JwtService jwtService) {
		this.clock = clock;
		this.workspaceRepository = workspaceRepository;
		this.workspaceMembershipRepository = workspaceMembershipRepository;
		this.jwtService = jwtService;
	}

	@Override
	@Transactional
	public CreateWorkspaceResult create(CreateWorkspaceCommand command) {
		// Normalize name
		// Set clock
		// Create Workspace
		// Create WorkspaceMembership
		// Generate new access token
		// Log
		// Return result

		return null;
	}

}
