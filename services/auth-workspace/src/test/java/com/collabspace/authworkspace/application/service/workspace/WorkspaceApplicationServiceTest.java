package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleCommand;
import com.collabspace.authworkspace.application.port.in.workspace.ChangeMemberRoleResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MemberRoleChangedEvent;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceEventPublisher;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.AccessToken;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.domain.exception.AlreadyMemberException;
import com.collabspace.authworkspace.domain.exception.InvitedUserNotFoundException;
import com.collabspace.authworkspace.domain.exception.LastAdminInvariantException;
import com.collabspace.authworkspace.domain.exception.TargetNotMemberException;
import com.collabspace.authworkspace.domain.model.auth.User;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceApplicationService")
class WorkspaceApplicationServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-06-04T10:00:00Z");

	private static final UUID USER_ID = UUID.randomUUID();

	private static final UUID WORKSPACE_ID = UUID.randomUUID();

	private static final UUID ADMIN_ID = UUID.randomUUID();

	private static final UUID INVITED_USER_ID = UUID.randomUUID();

	private static final UUID MEMBER_ID = UUID.randomUUID();

	private static final String INVITED_EMAIL = "bob@example.com";

	private static final String TEST_IP = "192.0.2.1";

	@Mock
	private WorkspaceRepository workspaceRepository;

	@Mock
	private WorkspaceMembershipRepository workspaceMembershipRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private MembershipStalenessRepository membershipStalenessRepository;

	@Mock
	private WorkspaceEventPublisher workspaceEventPublisher;

	@Mock
	private JwtService jwtService;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private WorkspaceApplicationService service;

	private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

	@BeforeEach
	void attachLogCapture() {
		Logger logger = (Logger) LoggerFactory.getLogger(WorkspaceApplicationService.class);
		logCapture.start();
		logger.addAppender(logCapture);
	}

	@AfterEach
	void detachLogCapture() {
		Logger logger = (Logger) LoggerFactory.getLogger(WorkspaceApplicationService.class);
		logger.detachAppender(logCapture);
		logCapture.stop();
	}

	@BeforeEach
	void setup() {
		// lenient: the invite() user-not-found path deliberately never reaches the
		// transaction manager at all -- see inviteThrowsInvitedUserNotFoundException...,
		// which asserts that directly via verifyNoInteractions(transactionManager).
		lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
		service = new WorkspaceApplicationService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), workspaceRepository,
				workspaceMembershipRepository, userRepository, membershipStalenessRepository, workspaceEventPublisher,
				jwtService, new CommitThenAction(transactionManager));
	}

	@Test
	@DisplayName("rolls back and propagates when the membership insert fails after the workspace insert")
	void createMembershipInsertFailsRollsBackAndPropagates() {
		CreateWorkspaceCommand command = new CreateWorkspaceCommand("Engineering", "Engineering workspace", USER_ID,
				Optional.of(TEST_IP));
		when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class)))
			.thenThrow(new DataIntegrityViolationException("simulated membership insert failure"));

		assertThrows(DataIntegrityViolationException.class, () -> service.create(command));

		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verify(workspaceMembershipRepository, never()).findByUserId(any());
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("commits the workspace and membership even when token signing fails afterwards")
	void createTokenSigningFailsAfterCommitPropagatesButPersistsWorkspace() {
		CreateWorkspaceCommand command = new CreateWorkspaceCommand("Engineering", "Engineering workspace", USER_ID,
				Optional.of(TEST_IP));
		when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		when(workspaceMembershipRepository.findByUserId(USER_ID)).thenReturn(List.of());
		when(jwtService.issueAccessToken(anyString(), anyList()))
			.thenThrow(new IllegalStateException("simulated signing failure"));

		assertThrows(IllegalStateException.class, () -> service.create(command));

		verify(transactionManager).commit(transactionStatus);
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
		verify(workspaceRepository).save(any(Workspace.class));
		verify(workspaceMembershipRepository).save(any(WorkspaceMembership.class));
	}

	private User invitedUser() {
		return new User(INVITED_USER_ID, "Bob", INVITED_EMAIL, Optional.empty(), FIXED_INSTANT, FIXED_INSTANT);
	}

	private InviteMemberCommand inviteCommand(Optional<String> role) {
		return new InviteMemberCommand(ADMIN_ID, WORKSPACE_ID, INVITED_EMAIL, role, Optional.empty(), Optional.empty());
	}

	private WorkspaceMembership membershipWithRole(UUID userId, WorkspaceRole role) {
		return new WorkspaceMembership(UUID.randomUUID(), WORKSPACE_ID, userId, role, FIXED_INSTANT, FIXED_INSTANT);
	}

	private ChangeMemberRoleCommand changeRoleCommand(UUID adminId, UUID memberId, String role) {
		return new ChangeMemberRoleCommand(adminId, WORKSPACE_ID, memberId, role, Optional.empty(), Optional.empty());
	}

	@Test
	@DisplayName("invite: happy path returns the created membership and commits")
	void inviteHappyPathReturnsCreatedMembershipAndCommits() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		InviteMemberResult result = service.invite(inviteCommand(Optional.of("member")));

		assertThat(result.invitedUserId()).isEqualTo(INVITED_USER_ID);
		assertThat(result.email()).isEqualTo(INVITED_EMAIL);
		assertThat(result.role()).isEqualTo(WorkspaceRole.MEMBER);
		assertThat(result.workspaceId()).isEqualTo(WORKSPACE_ID);
		verify(transactionManager).commit(transactionStatus);
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
		verify(membershipStalenessRepository).markMembershipChanged(eq(INVITED_USER_ID), any());
		verify(workspaceEventPublisher).publishMemberInvited(any());
	}

	@Test
	@DisplayName("invite: normalizes the email to lowercase before the lookup")
	void inviteNormalizesEmailBeforeLookup() {
		InviteMemberCommand command = new InviteMemberCommand(ADMIN_ID, WORKSPACE_ID, "Bob@Example.com",
				Optional.empty(), Optional.empty(), Optional.empty());
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		service.invite(command);

		verify(userRepository).findByEmail(INVITED_EMAIL);
	}

	@Test
	@DisplayName("invite: defaults role to member when absent from the command")
	void inviteDefaultsRoleToMemberWhenAbsent() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		InviteMemberResult result = service.invite(inviteCommand(Optional.empty()));

		assertThat(result.role()).isEqualTo(WorkspaceRole.MEMBER);
	}

	@Test
	@DisplayName("invite: honors an explicit admin role")
	void inviteHonorsExplicitAdminRole() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		InviteMemberResult result = service.invite(inviteCommand(Optional.of("admin")));

		assertThat(result.role()).isEqualTo(WorkspaceRole.ADMIN);
	}

	@Test
	@DisplayName("invite: throws InvitedUserNotFoundException when the email is unregistered, without starting a transaction")
	void inviteThrowsInvitedUserNotFoundExceptionWhenEmailUnregistered() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.empty());

		assertThrows(InvitedUserNotFoundException.class, () -> service.invite(inviteCommand(Optional.empty())));

		verifyNoInteractions(workspaceMembershipRepository);
		verifyNoInteractions(transactionManager);
	}

	@Test
	@DisplayName("invite: translates the named unique-constraint violation into AlreadyMemberException and rolls back")
	void inviteTranslatesUniqueConstraintViolationToAlreadyMemberException() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class)))
			.thenThrow(new AlreadyMemberException());

		assertThrows(AlreadyMemberException.class, () -> service.invite(inviteCommand(Optional.empty())));

		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
	}

	@Test
	@DisplayName("invite: still succeeds, and still attempts the SNS publish, when the Redis marker write fails")
	void inviteStillSucceedsWhenMembershipMarkerWriteFails() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new DataAccessResourceFailureException("redis down")).when(membershipStalenessRepository)
			.markMembershipChanged(any(), any());

		InviteMemberResult result = service.invite(inviteCommand(Optional.empty()));

		assertThat(result).isNotNull();
		verify(workspaceEventPublisher).publishMemberInvited(any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("invite: still succeeds, and still attempts the Redis marker write, when the SNS publish fails")
	void inviteStillSucceedsWhenEventPublishFails() {
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(SdkException.create("sns down", null)).when(workspaceEventPublisher).publishMemberInvited(any());

		InviteMemberResult result = service.invite(inviteCommand(Optional.empty()));

		assertThat(result).isNotNull();
		verify(membershipStalenessRepository).markMembershipChanged(any(), any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("invite: still succeeds, and still attempts the Redis marker write, when the SNS publish fails to serialize the event")
	void inviteStillSucceedsWhenEventPublishFailsWithSerializationError() {
		// Simulates SnsWorkspaceEventPublisher's own IllegalStateException wrapper around
		// a
		// JsonProcessingException -- a different exception type than the SdkException
		// case
		// above, both of which the afterCommit isolation must catch equally.
		when(userRepository.findByEmail(INVITED_EMAIL)).thenReturn(Optional.of(invitedUser()));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new IllegalStateException("Failed to serialize MemberInvitedEvent")).when(workspaceEventPublisher)
			.publishMemberInvited(any());

		InviteMemberResult result = service.invite(inviteCommand(Optional.empty()));

		assertThat(result).isNotNull();
		verify(membershipStalenessRepository).markMembershipChanged(any(), any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("changeRole: no-op when requested role equals current role starts no transaction and touches no collaborator")
	void changeRoleNoOpWhenRequestedRoleEqualsCurrentRoleReturnsUnchangedResult() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin"));

		assertThat(result.role()).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(result.userId()).isEqualTo(MEMBER_ID);
		assertThat(result.accessToken()).isEmpty();
		verify(workspaceMembershipRepository, never()).save(any());
		verifyNoInteractions(transactionManager);
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("changeRole: throws TargetNotMemberException when the target has no membership, starting no transaction")
	void changeRoleThrowsTargetNotMemberExceptionWhenNoMembershipExists() {
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.empty());

		assertThrows(TargetNotMemberException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin")));

		verify(workspaceMembershipRepository, never()).save(any());
		verifyNoInteractions(transactionManager);
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("changeRole: promotion (other-directed) returns the updated membership, commits, and writes the marker")
	void changeRolePromotionOtherDirectedReturnsUpdatedMembershipAndCommits() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.MEMBER);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin"));

		assertThat(result.role()).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(result.accessToken()).isEmpty();
		verify(transactionManager).commit(transactionStatus);
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
		verify(membershipStalenessRepository).markMembershipChanged(eq(MEMBER_ID), any());
		verify(workspaceEventPublisher).publishRoleChanged(any());
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("changeRole: promotion never consults the last-admin invariant")
	void changeRolePromotionNeverConsultsLastAdminInvariant() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.MEMBER);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin"));

		verify(workspaceMembershipRepository, never()).countAdminsForUpdate(any());
	}

	@Test
	@DisplayName("changeRole: self-demotion when not the last admin returns a fresh access token and skips marker/event")
	void changeRoleSelfDemotionNotLastAdminReturnsFreshAccessToken() {
		WorkspaceMembership current = membershipWithRole(ADMIN_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, ADMIN_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn(new AccessToken("new-token", "jti-1"));

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, ADMIN_ID, "member"));

		assertThat(result.role()).isEqualTo(WorkspaceRole.MEMBER);
		assertThat(result.accessToken()).contains("new-token");
		verify(jwtService).issueAccessToken(eq(ADMIN_ID.toString()), anyList());
		verify(transactionManager).commit(transactionStatus);
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
	}

	@Test
	@DisplayName("changeRole: self-demotion as the last admin throws before ever minting a token")
	void changeRoleSelfDemotionLastAdminThrowsWithoutMintingToken() {
		WorkspaceMembership current = membershipWithRole(ADMIN_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, ADMIN_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(1);

		assertThrows(LastAdminInvariantException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, ADMIN_ID, "member")));

		verifyNoInteractions(jwtService);
		verify(workspaceMembershipRepository, never()).save(any());
		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("changeRole: other-directed demotion of the last admin throws and rolls back without marker/event/token")
	void changeRoleOtherDirectedDemotionLastAdminThrowsAndRollsBack() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(1);

		assertThrows(LastAdminInvariantException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member")));

		verify(workspaceMembershipRepository, never()).save(any());
		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("changeRole: trusts the post-lock re-read over the pre-lock snapshot and skips a redundant save when a concurrent request already applied the same change")
	void changeRoleReReadAfterLockSkipsRedundantSaveWhenAlreadyAtTargetRole() {
		// Simulates a concurrent request that demoted this exact member between the
		// pre-lock lookup and the post-lock re-read inside ensureAdminInvariant --
		// the second read must win, and since it already matches the requested role,
		// the save is redundant. countAdminsForUpdate=2 keeps the invariant itself out
		// of the way so this test isolates the re-read/skip-save behavior alone.
		WorkspaceMembership preLock = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		WorkspaceMembership postLock = membershipWithRole(MEMBER_ID, WorkspaceRole.MEMBER);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(preLock))
			.thenReturn(Optional.of(postLock));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(result.role()).isEqualTo(WorkspaceRole.MEMBER);
		verify(workspaceMembershipRepository, never()).save(any());
		verify(transactionManager).commit(transactionStatus);
		verify(membershipStalenessRepository).markMembershipChanged(eq(MEMBER_ID), any());
		verify(workspaceEventPublisher).publishRoleChanged(any());
	}

	@Test
	@DisplayName("changeRole: rolls back and propagates when the save fails, without marker/event/token side effects")
	void changeRoleSaveFailureRollsBackAndSkipsSideEffects() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.MEMBER);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class)))
			.thenThrow(new DataIntegrityViolationException("simulated save failure"));

		assertThrows(DataIntegrityViolationException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin")));

		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verifyNoInteractions(membershipStalenessRepository);
		verifyNoInteractions(workspaceEventPublisher);
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("changeRole: still succeeds, and still attempts the SNS publish, when the Redis marker write fails")
	void changeRoleStillSucceedsWhenMembershipMarkerWriteFails() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new DataAccessResourceFailureException("redis down")).when(membershipStalenessRepository)
			.markMembershipChanged(any(), any());

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(result).isNotNull();
		verify(workspaceEventPublisher).publishRoleChanged(any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("changeRole: still succeeds, and still attempts the Redis marker write, when the SNS publish fails")
	void changeRoleStillSucceedsWhenRoleChangedPublishFails() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(SdkException.create("sns down", null)).when(workspaceEventPublisher).publishRoleChanged(any());

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(result).isNotNull();
		verify(membershipStalenessRepository).markMembershipChanged(any(), any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("changeRole: still succeeds, and still attempts the Redis marker write, when the SNS publish fails to serialize the event")
	void changeRoleStillSucceedsWhenRoleChangedPublishFailsWithSerializationError() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new IllegalStateException("Failed to serialize MemberRoleChangedEvent")).when(workspaceEventPublisher)
			.publishRoleChanged(any());

		ChangeMemberRoleResult result = service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(result).isNotNull();
		verify(membershipStalenessRepository).markMembershipChanged(any(), any());
		verify(transactionManager).commit(transactionStatus);
	}

	@Test
	@DisplayName("changeRole: self-demotion commits the role change even when token signing fails afterwards")
	void changeRoleTokenSigningFailsAfterCommitPropagatesButPersistsRoleChange() {
		WorkspaceMembership current = membershipWithRole(ADMIN_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, ADMIN_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(anyString(), anyList()))
			.thenThrow(new IllegalStateException("simulated signing failure"));

		assertThrows(IllegalStateException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, ADMIN_ID, "member")));

		verify(transactionManager).commit(transactionStatus);
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
		verify(workspaceMembershipRepository).save(any(WorkspaceMembership.class));
	}

	@Test
	@DisplayName("changeRole: publishes the previous and new role from the pre-lock snapshot and persisted result, not re-derived")
	void changeRolePublishesEventWithPreciseRoleTransitionFields() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		ArgumentCaptor<MemberRoleChangedEvent> captor = ArgumentCaptor.forClass(MemberRoleChangedEvent.class);

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		verify(workspaceEventPublisher).publishRoleChanged(captor.capture());
		MemberRoleChangedEvent event = captor.getValue();
		assertThat(event.adminId()).isEqualTo(ADMIN_ID);
		assertThat(event.workspaceId()).isEqualTo(WORKSPACE_ID);
		assertThat(event.memberId()).isEqualTo(MEMBER_ID);
		assertThat(event.previousRole()).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(event.newRole()).isEqualTo(WorkspaceRole.MEMBER);
	}

	@Test
	@DisplayName("changeRole: logs member_role_changed at INFO with the role transition and a null jti for an other-directed change")
	void changeRoleSuccessOtherDirectedLogsEventWithNullJti() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.MEMBER);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		ChangeMemberRoleCommand command = new ChangeMemberRoleCommand(ADMIN_ID, WORKSPACE_ID, MEMBER_ID, "admin",
				Optional.of("corr-log-1"), Optional.of(TEST_IP));

		service.changeMemberRole(command);

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("event=member_role_changed")
					&& e.getFormattedMessage().contains("previousRole=MEMBER")
					&& e.getFormattedMessage().contains("newRole=ADMIN")
					&& e.getFormattedMessage().contains("correlationId=corr-log-1")
					&& e.getFormattedMessage().contains("jti=null"));
	}

	@Test
	@DisplayName("changeRole: logs member_role_changed at INFO with a non-null jti for a self-directed change")
	void changeRoleSelfDemotionSuccessLogsEventWithJti() {
		WorkspaceMembership current = membershipWithRole(ADMIN_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, ADMIN_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		when(jwtService.issueAccessToken(anyString(), anyList())).thenReturn(new AccessToken("token", "jti-log-1"));

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, ADMIN_ID, "member"));

		assertThat(logCapture.list)
			.anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("event=member_role_changed")
					&& e.getFormattedMessage().contains("jti=jti-log-1"));
	}

	@Test
	@DisplayName("changeRole: logs member_role_change_noop at INFO when the requested role already matches")
	void changeRoleNoOpLogsEventAtInfoLevel() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin"));

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("event=member_role_change_noop")
						&& e.getFormattedMessage().contains("role=admin"));
	}

	@Test
	@DisplayName("changeRole: logs member_role_change_rejected reason=target_not_member at WARN")
	void changeRoleTargetNotMemberLogsWarnWithReason() {
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.empty());

		assertThrows(TargetNotMemberException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "admin")));

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("event=member_role_change_rejected")
						&& e.getFormattedMessage().contains("reason=target_not_member"));
	}

	@Test
	@DisplayName("changeRole: logs member_role_change_rejected reason=last_admin_invariant at WARN")
	void changeRoleLastAdminInvariantLogsWarnWithReason() {
		WorkspaceMembership current = membershipWithRole(ADMIN_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, ADMIN_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(1);

		assertThrows(LastAdminInvariantException.class,
				() -> service.changeMemberRole(changeRoleCommand(ADMIN_ID, ADMIN_ID, "member")));

		assertThat(logCapture.list).anyMatch(
				e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("event=member_role_change_rejected")
						&& e.getFormattedMessage().contains("reason=last_admin_invariant"));
	}

	@Test
	@DisplayName("changeRole: logs membership_marker_write_failed at ERROR with the target's userId when the Redis marker write fails")
	void changeRoleMarkerWriteFailureLogsErrorWithTargetUserId() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new DataAccessResourceFailureException("redis down")).when(membershipStalenessRepository)
			.markMembershipChanged(any(), any());

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(logCapture.list).anyMatch(e -> e.getLevel() == Level.ERROR
				&& e.getFormattedMessage().contains("event=membership_marker_write_failed")
				&& e.getFormattedMessage().contains("userId=" + MEMBER_ID));
	}

	@Test
	@DisplayName("changeRole: logs member_role_changed_publish_failed at ERROR with the target's userId when the SNS publish fails")
	void changeRolePublishFailureLogsErrorWithTargetUserId() {
		WorkspaceMembership current = membershipWithRole(MEMBER_ID, WorkspaceRole.ADMIN);
		when(workspaceMembershipRepository.findByWorkspaceIdAndUserId(WORKSPACE_ID, MEMBER_ID))
			.thenReturn(Optional.of(current));
		when(workspaceMembershipRepository.countAdminsForUpdate(WORKSPACE_ID)).thenReturn(2);
		when(workspaceMembershipRepository.save(any(WorkspaceMembership.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(SdkException.create("sns down", null)).when(workspaceEventPublisher).publishRoleChanged(any());

		service.changeMemberRole(changeRoleCommand(ADMIN_ID, MEMBER_ID, "member"));

		assertThat(logCapture.list).anyMatch(e -> e.getLevel() == Level.ERROR
				&& e.getFormattedMessage().contains("event=member_role_changed_publish_failed")
				&& e.getFormattedMessage().contains("userId=" + MEMBER_ID));
	}

}
