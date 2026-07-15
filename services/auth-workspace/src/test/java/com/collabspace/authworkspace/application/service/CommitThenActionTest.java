package com.collabspace.authworkspace.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommitThenAction")
class CommitThenActionTest {

	@Mock
	private PlatformTransactionManager transactionManager;

	@Mock
	private TransactionStatus transactionStatus;

	private CommitThenAction commitThenAction;

	@BeforeEach
	void setup() {
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
		commitThenAction = new CommitThenAction(transactionManager);
	}

	@Test
	@DisplayName("runs afterCommit and returns its result when writes succeed")
	void runWritesSucceedReturnsAfterCommitResult() {
		Runnable writes = mock(Runnable.class);

		String result = commitThenAction.run(writes, () -> "done");

		assertThat(result).isEqualTo("done");
		verify(writes).run();
	}

	@Test
	@DisplayName("commits before afterCommit runs")
	void runCommitsBeforeAfterCommitRuns() {
		Runnable writes = mock(Runnable.class);
		Runnable afterCommit = mock(Runnable.class);

		commitThenAction.run(writes, () -> {
			afterCommit.run();
			return null;
		});

		InOrder order = inOrder(writes, transactionManager, afterCommit);
		order.verify(writes).run();
		order.verify(transactionManager).commit(transactionStatus);
		order.verify(afterCommit).run();
	}

	@Test
	@DisplayName("rolls back and never runs afterCommit when writes throws")
	void runWritesThrowsRollsBackAndSkipsAfterCommit() {
		Runnable writes = () -> {
			throw new IllegalStateException("simulated write failure");
		};
		Runnable afterCommit = mock(Runnable.class);

		assertThrows(IllegalStateException.class, () -> commitThenAction.run(writes, () -> {
			afterCommit.run();
			return null;
		}));

		verify(transactionManager).rollback(transactionStatus);
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
		verify(afterCommit, never()).run();
	}

}
