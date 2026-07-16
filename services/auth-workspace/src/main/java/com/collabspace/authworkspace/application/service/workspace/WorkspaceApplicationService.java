package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberUseCase;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MemberInvitedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceEventPublisher;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.exception.AlreadyMemberException;
import com.collabspace.authworkspace.domain.exception.InvitedUserNotFoundException;
import com.collabspace.authworkspace.domain.model.auth.User;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceApplicationService implements CreateWorkspaceUseCase, InviteMemberUseCase {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceApplicationService.class);

	private final WorkspaceRepository workspaceRepository;

	private final WorkspaceMembershipRepository workspaceMembershipRepository;

	private final UserRepository userRepository;

	private final MembershipStalenessRepository membershipStalenessRepository;

	private final WorkspaceEventPublisher workspaceEventPublisher;

	private final JwtService jwtService;

	private final Clock clock;

	private final CommitThenAction commitThenAction;

	public WorkspaceApplicationService(Clock clock, WorkspaceRepository workspaceRepository,
			WorkspaceMembershipRepository workspaceMembershipRepository, UserRepository userRepository,
			MembershipStalenessRepository membershipStalenessRepository,
			WorkspaceEventPublisher workspaceEventPublisher, JwtService jwtService, CommitThenAction commitThenAction) {
		this.clock = clock;
		this.workspaceRepository = workspaceRepository;
		this.workspaceMembershipRepository = workspaceMembershipRepository;
		this.userRepository = userRepository;
		this.membershipStalenessRepository = membershipStalenessRepository;
		this.workspaceEventPublisher = workspaceEventPublisher;
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

		// Captured via array since CommitThenAction's `writes` is a Runnable, not a
		// Supplier -- and captured at all (rather than reusing the locals above) since
		// JPA
		// auditing sets createdAt/updatedAt from its own clock, not `now`.
		Workspace[] persistedWorkspace = new Workspace[1];
		WorkspaceMembership[] persistedMembership = new WorkspaceMembership[1];

		AccessToken accessToken = commitThenAction.run(() -> {
			persistedWorkspace[0] = workspaceRepository.save(workspace);
			persistedMembership[0] = workspaceMembershipRepository.save(workspaceMembership);
		}, () -> {
			List<WorkspaceMembership> userWorkspaces = workspaceMembershipRepository.findByUserId(command.userId());
			return jwtService.issueAccessToken(command.userId().toString(), userWorkspaces);
		});

		log.info("event=workspace_created userId={} workspaceId={} name={} ip={} jti={}", command.userId(),
				persistedWorkspace[0].id(), persistedWorkspace[0].name(), command.ipAddress().orElse(null),
				accessToken.jti());

		return new CreateWorkspaceResult(persistedWorkspace[0], persistedMembership[0].role(), accessToken.token());
	}

	@Override
	public InviteMemberResult invite(InviteMemberCommand command) {
		String normalisedEmail = command.email().trim().toLowerCase();
		String emailHash = CryptoUtils.sha256Hex(normalisedEmail);
		Instant now = clock.instant();

		User invitedUser = userRepository.findByEmail(normalisedEmail).orElseThrow(() -> {
			log.warn("event=member_invite_rejected reason=user_not_found emailHash={}", emailHash);
			return new InvitedUserNotFoundException();
		});

		WorkspaceRole role = WorkspaceRole.fromString(command.role().orElse(WorkspaceRole.MEMBER.getValue()));

		WorkspaceMembership workspaceMembership = new WorkspaceMembership(UUID.randomUUID(), command.workspaceId(),
				invitedUser.id(), role, now, now);

		WorkspaceMembership[] persistedMembership = new WorkspaceMembership[1];

		InviteMemberResult inviteMemberResult;
		try {
			inviteMemberResult = commitThenAction
				.run(() -> persistedMembership[0] = workspaceMembershipRepository.save(workspaceMembership), () -> {
					try {
						membershipStalenessRepository.markMembershipChanged(persistedMembership[0].userId(),
								persistedMembership[0].createdAt());
					}
					catch (DataAccessException ex) {
						log.error("event=membership_marker_write_failed invitedUserId={}",
								persistedMembership[0].userId(), ex);
					}
					try {
						MemberInvitedEvent event = new MemberInvitedEvent(command.adminId(),
								persistedMembership[0].workspaceId(), persistedMembership[0].userId(),
								invitedUser.email(), role, command.correlationId().orElse(null));
						workspaceEventPublisher.publishMemberInvited(event);
					}
					catch (SdkException | IllegalStateException ex) {
						// IllegalStateException: SnsWorkspaceEventPublisher wraps a
						// JsonProcessingException in this before ever calling SnsClient
						// --
						// that failure needs the same fail-open isolation as a
						// transport-level
						// SdkException, or a broken serialization would escape this
						// lambda and
						// turn an already-committed invite into an unhandled 500.
						log.error("event=member_invited_publish_failed invitedUserId={}",
								persistedMembership[0].userId(), ex);
					}

					return new InviteMemberResult(invitedUser.id(), invitedUser.email(), role,
							persistedMembership[0].workspaceId(), persistedMembership[0].createdAt());
				});
		}
		catch (AlreadyMemberException ex) {
			log.warn("event=member_invite_rejected reason=already_member invitedUserId={}", invitedUser.id());
			throw ex;
		}

		log.info(
				"event=member_invited userId={} invitedUserId={} emailHash={} workspaceId={} role={} ip={} correlationId={}",
				command.adminId(), invitedUser.id(), emailHash, command.workspaceId(), role.getValue(),
				command.ipAddress().orElse(null), command.correlationId().orElse(null));

		return inviteMemberResult;
	}

}
