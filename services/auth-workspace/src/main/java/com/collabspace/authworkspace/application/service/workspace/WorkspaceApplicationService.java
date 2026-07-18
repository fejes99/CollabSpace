package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleCommand;
import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleResult;
import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.RemoveMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.RemoveMemberUseCase;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MemberInvitedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.MemberRemovedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.MemberRoleChangedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceEventPublisher;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.application.util.CryptoUtils;
import com.collabspace.authworkspace.domain.exception.AlreadyMemberException;
import com.collabspace.authworkspace.domain.exception.CreatorSelfRemovalException;
import com.collabspace.authworkspace.domain.exception.InvitedUserNotFoundException;
import com.collabspace.authworkspace.domain.exception.LastAdminInvariantException;
import com.collabspace.authworkspace.domain.exception.TargetNotMemberException;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceApplicationService
		implements CreateWorkspaceUseCase, InviteMemberUseCase, ChangeMemberRoleUseCase, RemoveMemberUseCase {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceApplicationService.class);

	private static final String EVENT_MARKER_WRITE_FAILED = "membership_marker_write_failed";

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
		}, () -> mintAccessToken(command.userId()));

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
					logAndContinueOnFailure(EVENT_MARKER_WRITE_FAILED, persistedMembership[0].userId(),
							() -> membershipStalenessRepository.markMembershipChanged(persistedMembership[0].userId(),
									persistedMembership[0].createdAt()));

					logAndContinueOnFailure("member_invited_publish_failed", persistedMembership[0].userId(), () -> {
						MemberInvitedEvent event = new MemberInvitedEvent(command.adminId(),
								persistedMembership[0].workspaceId(), persistedMembership[0].userId(),
								invitedUser.email(), role, command.correlationId().orElse(null));
						workspaceEventPublisher.publishMemberInvited(event);
					});

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

	@Override
	public ChangeMemberRoleResult changeMemberRole(ChangeMemberRoleCommand command) {
		WorkspaceRole role = WorkspaceRole.fromString(command.role());

		WorkspaceMembership currentMembership = workspaceMembershipRepository
			.findByWorkspaceIdAndUserId(command.workspaceId(), command.memberId())
			.orElseThrow(() -> {
				log.warn("event=member_role_change_rejected reason=target_not_member workspaceId={} memberId={}",
						command.workspaceId(), command.memberId());
				return new TargetNotMemberException();
			});

		if (currentMembership.role().equals(role)) {
			log.info("event=member_role_change_noop workspaceId={} memberId={} role={}", command.workspaceId(),
					command.memberId(), role.getValue());
			return new ChangeMemberRoleResult(currentMembership.workspaceId(), currentMembership.userId(),
					currentMembership.role(), currentMembership.updatedAt(), Optional.empty());
		}

		WorkspaceMembership[] persistedMembership = new WorkspaceMembership[1];
		boolean isDemotion = currentMembership.role() == WorkspaceRole.ADMIN && role == WorkspaceRole.MEMBER;

		try {
			return commitThenAction.run(() -> {
				WorkspaceMembership target = isDemotion ? verifyAdminInvariant(currentMembership) : currentMembership;
				persistedMembership[0] = target.role().equals(role) ? target
						: workspaceMembershipRepository.save(target.changeRole(role));
			}, () -> changeMemberRoleAfterCommit(command, currentMembership, persistedMembership));
		}
		catch (LastAdminInvariantException ex) {
			log.warn("event=member_role_change_rejected reason=last_admin_invariant workspaceId={} memberId={}",
					command.workspaceId(), command.memberId());
			throw ex;
		}
	}

	private ChangeMemberRoleResult changeMemberRoleAfterCommit(ChangeMemberRoleCommand command,
			WorkspaceMembership currentMembership, WorkspaceMembership[] persistedMembership) {
		boolean isSelf = command.adminId().equals(command.memberId());
		Optional<String> accessToken = Optional.empty();
		String jti = null;

		if (isSelf) {
			AccessToken token = mintAccessToken(command.adminId());
			accessToken = Optional.of(token.token());
			jti = token.jti();
		}
		else {
			logAndContinueOnFailure(EVENT_MARKER_WRITE_FAILED, persistedMembership[0].userId(),
					() -> membershipStalenessRepository.markMembershipChanged(persistedMembership[0].userId(),
							persistedMembership[0].updatedAt()));

			logAndContinueOnFailure("member_role_changed_publish_failed", persistedMembership[0].userId(), () -> {
				MemberRoleChangedEvent event = new MemberRoleChangedEvent(command.adminId(),
						persistedMembership[0].workspaceId(), persistedMembership[0].userId(), currentMembership.role(),
						persistedMembership[0].role(), command.correlationId().orElse(null));
				workspaceEventPublisher.publishRoleChanged(event);
			});
		}

		log.info(
				"event=member_role_changed adminId={} memberId={} workspaceId={} previousRole={} newRole={} ip={} correlationId={} jti={}",
				command.adminId(), persistedMembership[0].userId(), persistedMembership[0].workspaceId(),
				currentMembership.role(), persistedMembership[0].role(), command.ipAddress().orElse(null),
				command.correlationId().orElse(null), jti);

		return new ChangeMemberRoleResult(persistedMembership[0].workspaceId(), persistedMembership[0].userId(),
				persistedMembership[0].role(), persistedMembership[0].updatedAt(), accessToken);
	}

	@Override
	public void removeMember(RemoveMemberCommand command) {
		Instant now = clock.instant();

		WorkspaceMembership currentMembership = workspaceMembershipRepository
			.findByWorkspaceIdAndUserId(command.workspaceId(), command.memberId())
			.orElseThrow(() -> {
				log.warn("event=member_removal_rejected reason=target_not_member workspaceId={} memberId={}",
						command.workspaceId(), command.memberId());
				return new TargetNotMemberException();
			});

		if (command.adminId().equals(command.memberId())) {
			Workspace currentWorkspace = workspaceRepository.findById(command.workspaceId())
				.orElseThrow(() -> new IllegalStateException("WorkspaceMembership " + currentMembership.id()
						+ " references workspace " + command.workspaceId() + ", which does not exist"));

			if (command.adminId().equals(currentWorkspace.createdByUserId())) {
				log.warn("event=member_removal_rejected reason=creator_self_removal workspaceId={} memberId={}",
						command.workspaceId(), command.memberId());
				throw new CreatorSelfRemovalException();
			}
		}

		boolean targetIsAdmin = currentMembership.role() == WorkspaceRole.ADMIN;

		try {
			commitThenAction.run(() -> {
				if (targetIsAdmin) {
					verifyAdminInvariant(currentMembership);
				}

				int changedRows = workspaceMembershipRepository.deleteByWorkspaceIdAndUserId(command.workspaceId(),
						command.memberId());
				if (changedRows == 0) {
					throw new TargetNotMemberException();
				}
			}, () -> {
				logAndContinueOnFailure(EVENT_MARKER_WRITE_FAILED, command.memberId(),
						() -> membershipStalenessRepository.markMembershipChanged(command.memberId(), now));
				logAndContinueOnFailure("member_removed_publish_failed", command.memberId(), () -> {
					MemberRemovedEvent event = new MemberRemovedEvent(command.adminId(), command.workspaceId(),
							command.memberId(), command.correlationId().orElse(null));
					workspaceEventPublisher.publishMemberRemoved(event);
				});

				log.info(
						"event=member_removed adminId={} targetUserId={} workspaceId={} previousRole={} ip={} correlationId={}",
						command.adminId(), command.memberId(), command.workspaceId(), currentMembership.role(),
						command.ipAddress().orElse(null), command.correlationId().orElse(null));
				return null;
			});
		}
		catch (LastAdminInvariantException ex) {
			log.warn("event=member_removal_rejected reason=last_admin_invariant workspaceId={} memberId={}",
					command.workspaceId(), command.memberId());
			throw ex;
		}
	}

	private AccessToken mintAccessToken(UUID userId) {
		List<WorkspaceMembership> memberships = workspaceMembershipRepository.findByUserId(userId);
		return jwtService.issueAccessToken(userId.toString(), memberships);
	}

	// SdkException: SNS transport failure. IllegalStateException:
	// SnsWorkspaceEventPublisher
	// wraps a JsonProcessingException in this before ever calling SnsClient -- both need
	// the
	// same fail-open isolation as a DataAccessException from the Redis marker write, or a
	// broken serialization would escape this lambda and turn an already-committed write
	// into
	// an unhandled 500.
	private void logAndContinueOnFailure(String failureEvent, UUID userId, Runnable action) {
		try {
			action.run();
		}
		catch (DataAccessException | SdkException | IllegalStateException ex) {
			log.error("event={} userId={}", failureEvent, userId, ex);
		}
	}

	private WorkspaceMembership verifyAdminInvariant(WorkspaceMembership membership) {
		int adminCount = workspaceMembershipRepository.countAdminsForUpdate(membership.workspaceId());

		WorkspaceMembership latest = workspaceMembershipRepository
			.findByWorkspaceIdAndUserId(membership.workspaceId(), membership.userId())
			.orElseThrow(TargetNotMemberException::new);

		if (adminCount == 1 && latest.role() == WorkspaceRole.ADMIN) {
			throw new LastAdminInvariantException();
		}
		return latest;
	}

}
