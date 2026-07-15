package com.collabspace.authworkspace.application.service.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceMembershipRepository;
import com.collabspace.authworkspace.application.port.out.workspace.WorkspaceRepository;
import com.collabspace.authworkspace.application.service.CommitThenAction;
import com.collabspace.authworkspace.application.service.JwtService;
import com.collabspace.authworkspace.domain.model.workspace.Workspace;
import com.collabspace.authworkspace.domain.model.workspace.WorkspaceMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceApplicationService")
class WorkspaceApplicationServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-06-04T10:00:00Z");

	private static final UUID USER_ID = UUID.randomUUID();

	private static final String TEST_IP = "192.0.2.1";

	@Mock
	private WorkspaceRepository workspaceRepository;

	@Mock
	private WorkspaceMembershipRepository workspaceMembershipRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private WorkspaceApplicationService service;

	@BeforeEach
	void setup() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
		service = new WorkspaceApplicationService(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), workspaceRepository,
				workspaceMembershipRepository, jwtService, new CommitThenAction(transactionManager));
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

}
