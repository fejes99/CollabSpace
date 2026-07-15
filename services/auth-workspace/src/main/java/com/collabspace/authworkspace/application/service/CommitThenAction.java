package com.collabspace.authworkspace.application.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

// Runs `writes` in its own transaction that commits before `afterCommit` runs -- unlike
// declarative @Transactional, which only commits after the whole annotated method (including
// any post-write side effect) returns. Left at PROPAGATION_REQUIRED, not REQUIRES_NEW: see
// ADR-034 for why REQUIRES_NEW was tried and reverted (it breaks @Transactional-test-rollback,
// since it suspends the test's own ambient transaction instead of joining it).
@Component
public class CommitThenAction {

	private final TransactionTemplate transactionTemplate;

	public CommitThenAction(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public <T> T run(Runnable writes, Supplier<T> afterCommit) {
		transactionTemplate.executeWithoutResult(status -> writes.run());
		return afterCommit.get();
	}

}
