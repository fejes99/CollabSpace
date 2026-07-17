package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import com.collabspace.authworkspace.application.port.out.auth.UserRepository;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceEventPublisher;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.domain.exception.AlreadyMemberException;
import com.collabspace.authworkspace.domain.exception.InvitedUserNotFoundException;
import com.collabspace.authworkspace.domain.model.auth.User;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

}
