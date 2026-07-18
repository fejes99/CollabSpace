# Plan: Remove Member

**Branch:** `feat/auth/remove-member`
**Service:** auth-workspace
**Tier:** Full

---

## 1. Slice statement

A workspace admin can remove a member from a workspace, including themselves; the removal is rejected if it would leave the workspace with zero admins, or if the caller is trying to remove themselves as the workspace's creator.

**IN**
- New endpoint `DELETE /v1/workspaces/{workspaceId}/members/{userId}`, admin-only (`hasWorkspaceRole(#workspaceId, 'admin')`, reused unchanged from PR 9/10) — deletes the target's membership row.
- Last-admin invariant: reject `422` if the removal would leave the workspace with zero admins — reuses PR 10's `countAdminsForUpdate` locking machinery as-is (same lock, same order, no new query needed for this part).
- Creator-self-removal block: the workspace creator can never remove their own membership (`422`); any *other* admin can remove the creator (mirrors the demotion precedent from PR 10's plan §7). Self- and other-directed removal both return plain `204`, with a `membership-changed-at` marker write + `member.removed` SNS publish for both — no token reissue for either (see §7/§8 for why self-removal differs from self-demote here).

**Scope note:** the creator-self-removal rule was not part of PR 11's original one-line scoping in `notes/auth-workspace-prs.md` (which only mentioned reusing the last-admin invariant) — it surfaced from re-reading `authorization.md` closely during planning. It's kept bundled into this slice rather than split into a separate PR: the mechanical reuse (last-admin lock) and the new rule (creator identity check) both live on the same code path in the same endpoint, and splitting them would mean two plan docs and two PRs touching the exact same few lines of the same method. Named explicitly here so the scope expansion is a conscious choice, not a silent one.

**OUT**
- Workspace deletion — a separate admin-only capability per `authorization.md`'s roles table, not built yet, not part of this slice. It is *not* the escape hatch for a creator who wants out, either — another admin removing the creator is the actual path (see IN bullet 3), so this PR doesn't depend on workspace-deletion existing.
- Ownership/creator transfer — `Workspace.createdByUserId` stays immutable; no "reassign creator" mechanism. Out of scope per `authorization.md`'s existing "no distinct owner role" stance.
- Closing the residual 3-way admin-set staleness race between concurrent promote / remove-member / remove-member requests (documented, not fixed — see §6). Fixing it would mean modifying already-merged PR 10 code (making promotion take the admin-set lock too); this slice explicitly does not touch PR 10.

---

## 2. User-visible behavior

- Admin caller removing another member receives `204 No Content`; that member's existing tokens are rejected (`401 claims-stale`) on their next request until refreshed.
- Admin caller removing themselves (not the creator) receives `204 No Content` with no body. Per `authentication.md`'s "no delay, ever" rule for self-directed changes, the client is expected to immediately call `POST /v1/auth/refresh` afterward rather than wait for a stale-token rejection on some future request. The server also writes the `membership-changed-at` marker as a defense-in-depth backstop, in case a client doesn't follow that contract.
- Non-admin caller receives `403 Forbidden`.
- Unauthenticated caller receives `401 Unauthorized`.
- Removing a `userId` with no membership in the workspace receives `404 Not Found`.
- Removing the workspace's last admin (self or otherwise) receives `422 Unprocessable Entity`, even under concurrent requests targeting different admins.
- The creator attempting to remove their own membership receives `422 Unprocessable Entity`, regardless of current admin count.
- Any other admin removing the creator succeeds (`204`), subject to the same last-admin invariant as any other admin removal.

---

## 3. API contract

**Path:** `DELETE /v1/workspaces/{workspaceId}/members/{userId}`
**Auth:** Bearer JWT required; admin-only via `hasWorkspaceRole(#workspaceId, 'admin')` (reused unchanged from PR 9/10).
**Request body:** None.
**Response body (happy path):** None — `204 No Content`.

**Status codes**

| Code | Scenario |
|---|---|
| 204 | Removal succeeded (self or other-directed) |
| 400 | Malformed `workspaceId`/`userId` path segment |
| 401 | Missing/invalid JWT |
| 403 | Caller not a member of the workspace (`authorization/not-a-member`), or member but not admin (`authorization/insufficient-role`) |
| 404 | `userId` has no membership in `{workspaceId}` (`workspace/target-not-a-member`) |
| 422 | Removal would leave zero admins (`workspace/last-admin-invariant`), or caller is the creator trying to remove themselves (`workspace/creator-self-removal`) |

**Note on the two `422` types:** they are not interchangeable from a client's perspective. `last-admin-invariant` is retryable — promote another admin and try again. `creator-self-removal` is permanent for that caller — no sequence of retries changes the outcome; only another admin can remove the creator. A generic "422 → offer retry" client handler would give the creator a misleading message. Client-facing documentation (out of this service's scope, but worth flagging) should treat these two `type` values differently, not just their shared status code.

---

## 4. Data model changes

None — no migration needed, reuses the existing `workspace_memberships` table (`V4__create_workspaces_and_memberships.sql`) and its `role` column exactly as PR 10 left it.

Implementation note (not a schema change, but data-access layer additions needed):
- `WorkspaceMembershipRepository` needs a delete capability that reports affected-row count, per the double-delete idempotency decision in §6. **Do not use a Spring Data derived `deleteBy...`/`removeBy...` method** — despite matching the existing derived-query style in `WorkspaceMembershipJpaRepository`, Spring Data implements those as a `SELECT` followed by `EntityManager.remove()` per matched entity (so JPA lifecycle callbacks fire), not a single atomic SQL `DELETE`. That makes the "0 rows affected → already gone" idempotency guarantee depend on entity-lifecycle behavior under concurrency rather than a true database row count. Use a custom `@Modifying @Query("DELETE FROM WorkspaceMembershipEntity m WHERE m.workspaceId = :workspaceId AND m.userId = :userId")` returning `int`/`long` instead — Hibernate compiles this to one bulk `DELETE` statement, and the returned count is the database driver's actual affected-row count. `WorkspaceMembershipEntity` has no `@PreRemove` callbacks or cascade relationships that would need entity-level removal semantics, so there's no downside to the bulk form here.
- `WorkspaceRepository` has no `findById` (only `save`) — needed to read `createdByUserId` for the creator-self-removal check.

---

## 5. Validation rules

No request body, so no field-level validation in the usual sense. The only inputs are the two path parameters:

| Field | Constraint | Error |
|---|---|---|
| `workspaceId` | Must parse as UUID | 400, `validation/invalid-path-parameter` |
| `userId` | Must parse as UUID | 400, `validation/invalid-path-parameter` |

Both handled by the existing `MethodArgumentTypeMismatchException` handler in `GlobalExceptionHandler` — no new validation code needed, same as every other path-templated route today.

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| Malformed `workspaceId`/`userId` path segment | 400 | |
| Unauthenticated | 401 | |
| Caller not a member of `{workspaceId}` (including nonexistent workspace) | 403, `not-a-member` | masks workspace existence, per `authorization.md` |
| Caller is a member but not admin | 403, `insufficient-role` | |
| `userId` has no membership in `{workspaceId}` | 404, `target-not-a-member` | |
| Caller removes themselves and is the workspace creator | 422, `creator-self-removal` | fires regardless of current admin count — an unconditional rule, not gated by the last-admin check |
| Caller removes themselves, is the creator, **and** is currently the workspace's sole admin | 422, `creator-self-removal` (not `last-admin-invariant`) | the creator-self-removal check runs first and unconditionally, before the last-admin lock is ever acquired — it's the more specific and more permanent of the two errors, and the correct one for the client to see |
| Another admin removes the creator | 204 | allowed — still subject to the last-admin invariant like any other removal |
| Removing the workspace's only admin (self or other, non-creator) | 422, `last-admin-invariant` | |
| Two concurrent requests each removing a *different* admin in a two-admin workspace | one `204`, one `422` | requires the count-and-delete to be atomic — reuses PR 10's `countAdminsForUpdate` row lock, same acquisition order as change-role to avoid a cross-endpoint deadlock |
| Two concurrent `DELETE` requests for the same target (true race), or a client retrying a `DELETE` it already received a `204` for but didn't observe (timeout, dropped response) | one `204`, one `404` | second call's delete affects 0 rows → treated as `target-not-a-member`, mirrors the optimistic-write-then-inspect pattern already used for invite-member's unique-constraint race. This is intentional REST idempotency, not a bug — client retry logic should treat a `404` on a repeated `DELETE` as "already done," not as a failure. |
| Self-removal (non-creator) | 204 | No `accessToken` in response (204 has no body) — client contract requires an immediate `POST /v1/auth/refresh` call, per `authentication.md`'s no-delay rule for self-directed changes. Marker write still happens as a backstop. |
| Other-directed removal | 204 | `membership-changed-at` marker written for target; target's existing tokens rejected `401 claims-stale` until refresh |
| `membership-changed-at` marker write fails | 204 | fail open, log ERROR (`event=membership_marker_write_failed`) |
| `member.removed` SNS publish fails | 204 | fail open, log ERROR (`event=member_removed_publish_failed`) |

**Known limitation (not a resolved edge case):** a narrow 3-way race between a concurrent promotion, a removal of the just-promoted user, and a removal of the original admin can theoretically still reach zero admins, because promotions don't participate in the admin-set lock. Documented, not fixed in this slice — closing it would require modifying already-merged PR 10 code (making promotion also acquire the admin-set lock purely for mutual exclusion). Accepted at the same risk tier as ADR-038's existing "role changes are infrequent per workspace" tradeoff, given it requires three near-simultaneous role-changing requests against one workspace to trigger.

---

## 7. Authorization

- Caller must be authenticated (valid, non-blocklisted, non-stale JWT) — existing filter chain, unchanged.
- Caller must hold `admin` role in `{workspaceId}` — `@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`, reused unchanged from PR 9/10. No new authorization mechanism.
- Unauthenticated: `401`.
- Authenticated, not a member: `403 not-a-member`.
- Authenticated, member but not admin: `403 insufficient-role`.
- No separate authorization rule distinguishes self- from other-directed targets — an admin is equally permitted to target either. That's an application-layer behavior fork (client-refresh contract vs. marker write), not a permission difference — consistent with how PR 10 treated the same distinction for role changes.
- **Target-not-a-member (404), last-admin invariant (422), and creator-self-removal (422) are all business invariants, not authorization** — handled at the service layer, per `authorization.md`'s explicit authorization-vs-invariant distinction. `@PreAuthorize` evaluates before any of these run, so a non-admin cannot use them as a membership-probing side channel.
- The creator-self-removal rule is new relative to PR 9/10: `authorization.md` §"Workspace ownership and admin handoff" states creators can never remove themselves, but says nothing about others removing them — so unlike the caller-role check above, this one *does* look at identity (`targetUserId == callerId == workspace.createdByUserId`), which is why it's classified as an invariant, not authorization.

---

## 8. Observability

**Audit events** (new rows for `authentication.md`'s table):

| Event | Log fields |
|---|---|
| Member removed (self) | `userId`, `workspaceId`, `previousRole`, `ip`, `correlationId` |
| Member removed (other) | `userId` (admin), `workspaceId`, `targetUserId`, `previousRole`, `ip`, `correlationId` |

Note: unlike "Member role changed (self)," neither removed-event row carries `jti` — self-removal no longer mints a token synchronously (per §7), so there's no new `jti` at removal time; the client's subsequent `POST /v1/auth/refresh` call generates its own `jti`, already covered by the existing "Token refresh" audit event.

**Documentation follow-up (Phase 5 Polish, not this plan's content):** `authentication.md`'s audit-events table has an explanatory paragraph directly beneath it that accounts for why `jti` is present or absent on every existing row. Adding these two new rows without extending that paragraph leaves two unexplained blanks in a table that currently explains every one — add a sentence there covering why `Member removed (self)` has no `jti`, mirroring the existing sentence for `Member role changed (self)`'s other-directed counterpart.

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful removal | INFO | `event=member_removed adminId={} targetUserId={} workspaceId={} previousRole={} ip={} correlationId={}` — `adminId` and `targetUserId` are equal on a self-removal, distinct on other-directed; this is how a reader tells the two apart from the log line alone, same convention as PR 10's `event=member_role_changed` line |
| Caller not a member | WARN | `event=member_removal_rejected reason=not_a_member` |
| Caller not admin | WARN | `event=member_removal_rejected reason=insufficient_role` |
| Target not a member | WARN | `event=member_removal_rejected reason=target_not_member` |
| Last-admin invariant violated | WARN | `event=member_removal_rejected reason=last_admin_invariant` |
| Creator self-removal attempted | WARN | `event=member_removal_rejected reason=creator_self_removal` |
| `membership-changed-at` marker write fails | ERROR | `event=membership_marker_write_failed targetUserId={}` |
| `member.removed` SNS publish fails | ERROR | `event=member_removed_publish_failed targetUserId={}` |

**Correlation ID:** propagated via existing MDC (`CorrelationIdFilter`), same as every other endpoint — no new work.

---

## 9. Out of scope

- Workspace deletion — a separate admin-only capability per `authorization.md`'s roles table, not built yet, not part of this slice. It is *not* the escape hatch for a creator who wants out, either — another admin removing the creator is the actual path (see §1 IN bullet 3), so this PR doesn't depend on workspace-deletion existing.
- Ownership/creator transfer — `Workspace.createdByUserId` stays immutable; no "reassign creator" mechanism. Out of scope per `authorization.md`'s existing "no distinct owner role" stance.
- Closing the residual 3-way admin-set staleness race (documented in §6, not fixed). Fixing it would mean modifying already-merged PR 10 code; this slice explicitly does not touch PR 10.
