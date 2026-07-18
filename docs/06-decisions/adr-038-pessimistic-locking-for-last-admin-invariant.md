# ADR-038: Pessimistic Locking for the Last-Admin Invariant

**Status:** Accepted
**Date:** 2026-07-17

---

## Context

`change-member-role` (`docs/03-services/auth-workspace/plans/change-member-role.md`) must reject a demotion that would leave a workspace with zero admins — including two concurrent requests that each demote a *different* admin in a two-admin workspace. Neither request's own last-admin check should see the other's in-flight write.

The invariant is a property of the *set* of admin membership rows in a workspace, not of any single row. That matters because it rules out the locking strategy this codebase would otherwise reach for by default: a naive "read count, compare, write" does the following under concurrency, with admins A and B as the workspace's only two admins:

- Request 1 (demote A): reads "other admins = {B}, count 1 ≥ 1" → proceeds.
- Request 2 (demote B): reads "other admins = {A}, count 1 ≥ 1" → proceeds.
- Both transactions write different rows (A's membership, B's membership) and both commit. Result: zero admins, invariant violated, and neither transaction ever saw anything wrong.

This PR builds the invariant check as reusable shape for PR 11 (remove-member), per the plan's §1 — whatever locking strategy is chosen here is inherited there.

---

## Decision

Use a row-locking read — `SELECT ... FOR UPDATE` over the workspace's admin membership rows — inside the same transaction as the count-and-update, via a new repository method (e.g. `countByWorkspaceIdAndRoleForUpdate(workspaceId, ADMIN)`). The second concurrent transaction targeting the same workspace's admins blocks until the first commits, then re-reads a count that reflects it — so it correctly resolves to `422`, not a second `200`.

This lock lives inside `CommitThenAction`'s `writes` lambda (ADR-034), not around the whole request. It is held only for the count-and-update DB round-trip and is released at commit — *before* `afterCommit` runs the token mint (self-demotion), the `membership-changed-at` marker write, or the SNS publish (other-directed). None of those post-commit steps run while the lock is held.

---

## Alternatives considered

**Per-row optimistic locking** (a `version`/`updated_at` column on `workspace_memberships`, JPA `@Version`-style CAS on write). Rejected — it doesn't cover this race at all. The two concurrent demotions write *different rows* (A's and B's), so neither transaction's version check ever conflicts with the other's. Optimistic locking's natural granularity is single-row; this invariant is multi-row.

**Workspace-level optimistic locking** (a new shared version/counter column on the workspace, bumped and CAS'd by every role change in that workspace). This would work — both concurrent transactions would contend on the same counter, and the loser would see a version mismatch. Rejected because it costs more than it saves: it's a schema change the plan's §4 explicitly avoids, it requires a retry loop (the loser has to re-read and re-derive that it should now return `422`, rather than getting that answer directly), and it concentrates contention onto one counter for every role change in the workspace regardless of which membership row is touched — worse locality than locking just the admin rows in question.

**Naive count-then-update, no locking.** Rejected — this is the literal bug in Context above; it's the case the plan calls out as needing to be prevented, not a real alternative.

**External/application-level lock** (e.g. a Redis lock keyed by `workspaceId`). Rejected — it introduces a second lock source (with its own failure modes: expiry, unavailability) to approximate a guarantee Postgres already gives natively, at the same granularity, inside the transaction that's doing the write anyway. No reason to reach outside the database for this.

---

## Consequences

**Positive:**
+ Correctly handles the cross-row race with no schema change — reuses the existing `workspace_memberships` table and its `role` column.
+ No client-facing retry semantics: the losing concurrent request gets the correct `422` on its first and only response, not a conflict it has to retry.
+ Lock scope is narrow by construction — confined to `CommitThenAction`'s `writes` lambda, released before token minting, the Redis marker write, or the SNS publish ever run.
+ Directly reusable by PR 11 (remove-member), which shares this exact invariant and can call the same repository method.

**Negative:**
− Introduces blocking under contention: concurrent role-change requests against the *same workspace's admins* queue rather than fail fast. Accepted because role changes are infrequent per workspace — this isn't a hot path where blocking would show up as a real throughput cost.
− This is the first `SELECT ... FOR UPDATE` in the codebase — every other query so far is a plain read or write. A future reader needs to understand row-level locking to know why this endpoint alone needs it (this ADR is that explanation).
− Couples the invariant check to Postgres's locking semantics specifically, rather than something more portable. Named here as a deliberate coupling to the already-chosen datastore, not an incidental one.

---

## Revisit when

- Traffic against a single workspace's role-change endpoint becomes high enough that lock contention is a measurable throughput concern (unlikely for this project's scale, but the trigger to name). At that point, the workspace-level optimistic-counter alternative above becomes worth re-costing.
- PR 11 (remove-member) needs to lock a different or wider set of rows than the admin-role rows this ADR covers — the shared repository method's shape may need to change.
- The count-and-lock query ever needs to move outside `CommitThenAction`'s `writes` boundary — the narrow-blast-radius consequence above depends on staying inside it (ADR-034).
