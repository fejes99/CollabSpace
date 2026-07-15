# ADR-034: Explicit Transaction Boundary for Post-Commit Actions

**Status:** Accepted
**Date:** 2026-07-15

---

## Context

ADR-032 establishes that self-directed membership changes (e.g. creating a workspace) reissue a fresh access token in the same response, derived from a live DB read of current memberships. The create-workspace plan (`docs/03-services/auth-workspace/plans/create-workspace.md`, §3) goes further and calls the ordering **implementation-critical**: the DB transaction must commit before the token is minted. If signing fails after a successful commit, the workspace must still be correctly persisted — a signing failure is a plain `500`, not a reason to lose data that already succeeded.

Spring's declarative `@Transactional` cannot express this. It's AOP-proxy-based: the surrounding transaction commits only *after* the annotated method returns. Since token minting happens inside `WorkspaceApplicationService.create()`'s body, putting `@Transactional` on `create()` itself means minting always runs *before* the commit, not after — the opposite of what's required. Worse, if minting throws, `@Transactional` rolls back everything in the method, including the already-correct workspace and membership inserts — exactly the data-loss the plan explicitly said not to allow.

This also needed to be verifiable without a full integration test: `WorkspaceApplicationServiceTest` had to prove that `commit()` fires *before* a token-signing failure, and `rollback()` fires (not `commit()`) when the membership insert itself fails — assertions declarative `@Transactional` gives no hook into from a plain unit test with mocked repositories.

---

## Decision

Wrap only the DB-write portion of `create()` — the workspace and membership inserts — in a `TransactionTemplate`, constructed from an injected `PlatformTransactionManager`, left at its default propagation (`PROPAGATION_REQUIRED`). `TransactionTemplate.executeWithoutResult(...)` commits its own transaction when the callback returns, *before* any code after that call executes. `create()` itself carries no `@Transactional` annotation; the `TransactionTemplate` block is the only transactional boundary in the method.

`PROPAGATION_REQUIRED`, not `REQUIRES_NEW`, is deliberate, not merely "the default we didn't touch." `REQUIRES_NEW` was tried first, on the reasoning that it would guarantee this block commits independently even if some future caller wraps `create()` in its own `@Transactional`. It was reverted after it broke `CreateWorkspaceIntegrationTest` in a way that pointed at a deeper problem: `REQUIRES_NEW` suspends whatever transaction is already active on the calling thread — which, inside a `@Transactional` Spring test (every integration test in this codebase uses this pattern to roll back its own data at the end), is the *test's own transaction*. The block then runs in a genuinely separate transaction/connection that cannot see anything the test set up but hasn't committed — concretely, `registerUser()`'s insert was invisible to it, and the subsequent workspace insert failed on `workspaces_created_by_user_id_fkey`. Beyond that one FK failure: any `REQUIRES_NEW` write that *doesn't* hit a missing-dependency error would still never be rolled back by the test's own transaction, since it isn't part of it — silently leaking real rows into the test database across runs. `REQUIRES_NEW`'s theoretical safety margin against a caller that doesn't exist today isn't worth trading away the test-rollback guarantee every other integration test in this service already depends on.

To make the block trivial (just two `.save()` calls, nothing to return out of the lambda), `Workspace` and `WorkspaceMembership` are fully constructed — including their `UUID.randomUUID()` ids — *before* entering the transactional block. This works because IDs are client-generated throughout this service already (`UserEntity`, `RefreshTokenEntity` both assign `UUID`s manually, no `@GeneratedValue`), so there's no dependency on the DB to produce an id before the membership can reference its workspace.

---

## Alternatives considered

**Declarative `@Transactional` on `create()`.** Rejected — as above, this commits *after* minting runs, not before, and rolls back the workspace along with any minting failure. This is the status quo everywhere else in the codebase, which is exactly why it took a real bug (verified failing, not just reasoned about) to notice it doesn't fit this case.

**Split into two methods on the same class, one `@Transactional`, called via self-invocation from `create()`.** Rejected — a classic Spring trap: calling `this.someTransactionalMethod()` from within the same bean bypasses the AOP proxy entirely, so the inner `@Transactional` is silently ignored. It would appear to work in casual testing (no exception, right-looking behavior under the happy path) and only reveal itself under the exact failure-mode tests this feature needed to write.

**Split into two separate beans** (a small `WorkspaceCreationTransactionalStep` collaborator with its own `@Transactional` method, injected into `WorkspaceApplicationService`). This avoids the self-invocation trap since the call crosses a real bean boundary through the proxy — genuinely viable. Rejected in favor of `TransactionTemplate` because the boundary being shaped is two lines (`.save()`, `.save()`); a whole extra class and constructor wire-up to hold them is overhead this specific case doesn't earn, and it splits "what's transactional" across two files instead of leaving it visible at the call site.

**Accept the ordering as-is and drop the plan's requirement.** Rejected — this is the exact scenario the plan called implementation-critical for a reason: a token-signing failure is a low-probability, service-wide event (broken signing key), not something that should also erase an already-successful, unrelated DB write.

**`PROPAGATION_REQUIRES_NEW` instead of the `PROPAGATION_REQUIRED` default.** Tried, then reverted — see Decision. It would close a real (if currently hypothetical) gap: a future caller wrapping `create()` in an outer `@Transactional` would otherwise cause this block to silently join that transaction instead of committing independently, defeating the commit-before-mint guarantee without any error to signal it. But it breaks a guarantee that's real *today*, not hypothetical: every integration test in this service relies on `@Transactional`-test-rollback, and `REQUIRES_NEW` suspends that transaction rather than participating in it. Trading a live, universal testing convention for protection against a caller that doesn't exist isn't the right side of that trade.

---

## Consequences

**Positive:**
+ Actually satisfies the plan's ordering requirement — verified directly in `WorkspaceApplicationServiceTest`: `transactionManager.commit(...)` fires even when `jwtService.issueAccessToken(...)` throws immediately afterward, and `transactionManager.rollback(...)` (not `commit`) fires when the membership insert itself fails, with `jwtService` never invoked in that path.
+ The transactional boundary is narrow and explicit — only the two inserts are inside it, not the token minting or the audit log line that follow, matching exactly what needed to be committed-before-mint and nothing more.
+ Testable without a real database or Testcontainers: mocking `PlatformTransactionManager` and stubbing `getTransaction(...)` to return a mock `TransactionStatus` is enough to exercise `TransactionTemplate`'s *real* exception-handling logic (the object under test is not itself mocked — only its transaction-manager dependency is), so the test proves the actual commit/rollback call sequence, not a stubbed-out approximation of it.

**Negative:**
− First and only use of `TransactionTemplate`/programmatic transaction management in this codebase — every other transactional method uses declarative `@Transactional`. A future reader unfamiliar with why has to find this ADR to know it's a deliberate exception, not a style inconsistency.
− `create()` carries no `@Transactional` annotation, which reads at a glance as "this method touches no transaction" — the opposite of true. A reviewer skimming for `@Transactional` would miss this method's actual DB-writing behavior entirely without reading the body.
− `WorkspaceApplicationService` now depends on `PlatformTransactionManager` directly, an infrastructure-layer type most application services in this codebase never need to reference — a small but real widening of what an "application service" is allowed to know about.
− `PROPAGATION_REQUIRED` means this block *can* be silently absorbed into a caller's already-active transaction — if a future caller ever wraps a call to `create()` in its own `@Transactional`, the commit-before-mint guarantee this ADR exists to provide breaks with no error, no test failure, and no signal that it happened. Accepted because `create()` has no such caller today and the alternative (`REQUIRES_NEW`) breaks something real right now to guard against something hypothetical — but this is the one bullet in this ADR most worth re-reading before adding a caller to `create()`.

---

## Revisit when

- **Before adding any caller that wraps `create()` (or a future method following this same pattern) in its own `@Transactional`.** `PROPAGATION_REQUIRED` means that caller's transaction would silently absorb this block, defeating the commit-before-mint guarantee with no error. If such a caller becomes necessary, `REQUIRES_NEW` needs to come back — paired with fixing the test-rollback interaction this ADR reverted it over (e.g. `TestTransaction.flagForCommit()`/`.end()`/`.start()` to explicitly commit test setup data before the `REQUIRES_NEW` block runs, applied deliberately in the specific tests that need it), not applied blindly to every integration test in this service.
- The next "reissue on mutation" endpoint ADR-032 implies (accept-invite, self-role-change) needs the same commit-before-mint guarantee — reuse this exact `TransactionTemplate` pattern (including the `PROPAGATION_REQUIRED` choice and its documented risk) rather than re-deriving it from scratch.
- This pattern repeats verbatim across three or more services/methods — at that point, the "no new class for two lines" argument against the separate-bean alternative weakens, and extracting a shared helper (or reconsidering the separate-collaborator-bean shape) becomes proportionate.
- Isolation requirements beyond `TransactionTemplate`'s default isolation level become necessary for any transactional block using this pattern — that wasn't deliberately tuned here, and should be revisited explicitly if a future case needs something stricter.
