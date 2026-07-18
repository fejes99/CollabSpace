# Plan: Change Member Role

**Branch:** `feat/auth/change-member-role`
**Service:** auth-workspace
**Tier:** Full

---

## 1. Slice statement

A workspace admin can change another member's (or their own) role between `admin` and `member`; the change is rejected if it would leave the workspace with zero admins.

**IN**
- Add new endpoint (`PATCH /v1/workspaces/{workspaceId}/members/{userId}`)
- Last-admin invariant: reject `422` if the change would leave zero admins (checked at demote-time, covers self-demote), enforced atomically against concurrent demotions (see §3)
- Self-directed change (caller changes their own role) reissues a fresh access token in the response, mirroring `create-workspace`; other-directed change writes the `membership-changed-at` marker instead (ADR-032)
- Idempotent no-op when requested role equals current role — no DB write, no event, no staleness write, `200` unchanged
- Publishes `member.role_changed` to the `workspace-events` SNS topic (mirrors `member.invited`)
- Adds the last-admin counting query and a generically-named invariant exception (see §3, §7) that PR 11 (remove-member) will reuse — **this PR builds only the shape both endpoints share, not any part of PR 11's actual remove logic**

**OUT**
- Remove member (PR 11 — separate endpoint, reuses the last-admin invariant built here)
- Inviting/creating memberships (PR 9, already shipped)
- Atomic single-call ownership transfer (per `authorization.md`, promote-then-demote stays two calls by design)

---

## 2. User-visible behavior

- Admin caller changing another member's role receives `200` with the resulting membership; that member's existing tokens are rejected (`401 claims-stale`) on their next request until refreshed.
- Admin caller demoting themselves receives `200` with the resulting membership **and** a fresh access token reflecting the demotion; their old token is not separately invalidated (natural expiry applies, but the demoted role is already correct client-side going forward).
- Setting a role to the value it already has receives `200` with the same shape, but nothing changes underneath — no new token, no one's session invalidated, no event published.
- Non-admin caller receives `403 Forbidden`.
- Unauthenticated caller receives `401 Unauthorized`.
- Changing the role of a userId with no membership in the workspace receives `404 Not Found`.
- Demoting the workspace's last admin (self or otherwise) receives `422 Unprocessable Entity`, even under concurrent requests targeting different admins.

---

## 3. API contract

**Path:** `PATCH /v1/workspaces/{workspaceId}/members/{userId}`
**Auth:** Bearer JWT required. Caller must hold `admin` role in `{workspaceId}` (`@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")` — reused as-is from PR 9, no new authorization mechanism).

**Request body**
```json
{ "role": "admin" }
```
`role` required — `admin` or `member`. Unlike invite-member's optional `role` (defaults to `member`), there's no sensible default for "change to what?", so this is `@NotBlank @ValidRole` rather than `@ValidRole` alone.

**Response body (200)**
```json
{
  "workspaceId": "3fa85f64-...",
  "userId": "3fa85f64-...",
  "role": "admin",
  "updatedAt": "2026-07-17T10:00:00Z",
  "accessToken": null
}
```
Same shape whether the role actually changed or the request was a no-op — the response represents resulting state, not a diff; `accessToken` is populated only when `userId == caller's own id` (self-demotion), `null` otherwise. `updatedAt` added for parity with `CreateWorkspaceResponse`/`InviteMemberResponse`, both of which include a timestamp reflecting when their mutation took effect — its absence here would read as an oversight, not a choice.

**Status codes**
| Code | Scenario |
|---|---|
| 200 | Role changed, or no-op (requested role == current role) |
| 400 | Validation failure (`role` missing or not `admin`/`member`) |
| 401 | Missing/invalid JWT |
| 403 | Caller not a member of the workspace (`authorization/not-a-member`), or member but not admin (`authorization/insufficient-role`) |
| 404 | `userId` has no membership in `{workspaceId}` (`workspace/target-not-a-member`) |
| 422 | Change would leave the workspace with zero admins (`workspace/last-admin-invariant`) |

**Ordering (implementation-critical):**
1. Look up the target's existing membership by `(workspaceId, userId)`. Missing → `404` before anything else runs.
2. Compare requested role to current role. Equal → short-circuit: build the response from current state, return `200`. Skip the invariant check, the DB write, the marker write, and the event publish entirely — a same-role request should never trip the last-admin check even when it targets the last admin, since nothing is actually changing.
3. Only if the comparison shows a real demotion (`admin` → `member`): check the last-admin invariant. **This check and the subsequent update must be atomic against concurrent demotions** — two requests demoting *different* admins in a two-admin workspace, racing near-simultaneously, must not both pass a naive "count other admins, then write" check (each would see the other admin still in place and both would succeed, leaving zero admins). Use a row-locking read on the workspace's admin membership rows (e.g. `SELECT ... FOR UPDATE`) within the same transaction as the count-and-update, so the second concurrent request blocks until the first commits and then re-reads a count that reflects it — see ADR-038 for why pessimistic locking was chosen over optimistic alternatives for this cross-row invariant. Promotions (`member` → `admin`) never need this check — they can only increase the admin count.
4. `writes` (via `CommitThenAction`, reused from ADR-034/invite-member): update the `workspace_memberships.role` column.
5. `afterCommit`: branch on `targetUserId == callerId`. Self → mint a fresh access token via `JwtService` (mirrors `create-workspace`). Other → write the `membership-changed-at:<targetUserId>` Redis marker and publish `member.role_changed`, each independently try/caught inside the lambda (mirrors invite-member's isolation of its two `afterCommit` steps, per ADR-034's Consequences note).

**New repository capability needed:** counting/locking admins in a workspace for the last-admin check (e.g. `workspaceMembershipRepository.countByWorkspaceIdAndRoleForUpdate(workspaceId, ADMIN)`) — not a schema change, just a new query method on the existing repository interface.

**Malformed `workspaceId`/`userId`:** either path segment failing UUID conversion returns `400` (standard `MethodArgumentTypeMismatchException` handling) — same as every other path-templated route today, no new code.

---

## 4. Data model changes

None. Reuses the existing `workspace_memberships` table and its `role` `CHECK` constraint from `V4__create_workspaces_and_memberships.sql`. Updates one existing row's `role` column; no new columns, tables, or indexes.

---

## 5. Validation rules

| Field | Constraint | Error |
|---|---|---|
| `role` | `@NotBlank` (required — no default, unlike invite-member) and `@ValidRole` (reused as-is, delegates to `WorkspaceRole.fromString`) | 400 + `errors` array |

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| Missing/blank `role` | 400 | |
| Invalid `role` value | 400 | |
| Missing request body | 400 | |
| Malformed `workspaceId`/`userId` path segment | 400 | see §3 |
| Unauthenticated | 401 | |
| Caller not a member of `{workspaceId}` (including nonexistent workspace) | 403, `not-a-member` | masks workspace existence, per `authorization.md` |
| Caller is a member but not admin | 403, `insufficient-role` | |
| `userId` has no membership in `{workspaceId}` | 404, `target-not-a-member` | distinct from invite-member's `user-not-found` — the user exists, just isn't a member of *this* workspace |
| Requested role == current role (any role, any target, including the last admin) | 200 | idempotent no-op — see §3 ordering, invariant check is skipped entirely; `@PreAuthorize` still runs first, so a non-admin can't reach this short-circuit as a membership-probing side channel |
| Demoting the workspace's only admin (self or other) | 422, `last-admin-invariant` | |
| Two concurrent requests each demoting a different admin in a two-admin workspace | one `200`, one `422` | requires the count-and-update to be atomic (row lock), see §3 — without it, both could pass and leave zero admins |
| Self-demotion (admin → member, `targetUserId == callerId`) | 200 | response carries fresh `accessToken`; no `membership-changed-at` write for self |
| Other-directed role change (`targetUserId != callerId`) | 200 | `membership-changed-at` written for target; target's existing tokens rejected `401 claims-stale` until refresh |
| Demoting the workspace creator via another admin's request | 200 (or 422 if last admin) | `authorization.md` only protects the creator from self-removal, not from being role-changed by someone else — no special-casing here |
| `membership-changed-at` marker write fails (other-directed only — never occurs for self-demotion, which reissues a token instead) | 200 | fail open, log ERROR (`event=membership_marker_write_failed`) |
| `member.role_changed` SNS publish fails | 200 | fail open, log ERROR (`event=member_role_changed_publish_failed`) |

---

## 7. Authorization

- Caller must be authenticated (valid, non-blocklisted JWT) — existing PR 7 filter chain.
- Caller must hold `admin` role in `{workspaceId}` — `@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`, reused unchanged from PR 9. No new authorization mechanism or exception types needed for the caller side. `@PreAuthorize` evaluates before the controller method body runs, so a non-admin cannot reach the no-op short-circuit (§3 step 2) as a way to probe workspace membership without tripping `403` first.
- Unauthenticated: `401`.
- Authenticated, not a member: `403 not-a-member`.
- Authenticated, member but not admin: `403 insufficient-role`.
- There is no separate authorization rule distinguishing self- from other-directed targets — an admin is equally permitted to change either. That distinction is an application-layer behavior fork (token reissue vs. staleness marker), not a permission difference.
- Target-not-a-member (404) and last-admin invariant (422) are both business invariants, not authorization — handled at the service layer, per `authorization.md`'s authorization-vs-invariant distinction.
- **Creator has no special protection from role changes.** `authorization.md` protects the workspace creator only from *removing themselves* (a PR 11 concern); it does not exempt them from being demoted or promoted by another admin via this endpoint. Stated explicitly here so a future reader doesn't assume creator immunity that doesn't exist.

---

## 8. Observability

**Audit events** (new rows added to `authentication.md`'s table as part of this PR):

| Event | Log fields |
|---|---|
| Member role changed (self) | `userId`, `workspaceId`, `previousRole`, `newRole`, `ip`, `jti`, `correlationId` |
| Member role changed (other) | `userId` (admin), `workspaceId`, `targetUserId`, `previousRole`, `newRole`, `ip`, `correlationId` |

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful role change | INFO | `event=member_role_changed` — `jti` present only on self-demotion |
| No-op (role unchanged) | INFO | `event=member_role_change_noop` |
| Caller not a member | WARN | `event=member_role_change_rejected`, `reason=not_a_member` |
| Caller not admin | WARN | `event=member_role_change_rejected`, `reason=insufficient_role` |
| Target not a member | WARN | `event=member_role_change_rejected`, `reason=target_not_member` |
| Last-admin invariant violated | WARN | `event=member_role_change_rejected`, `reason=last_admin_invariant` |
| Redis marker write fails (other-directed only — never fires for a self-demotion) | ERROR | `event=membership_marker_write_failed`, `targetUserId` |
| SNS publish fails | ERROR | `event=member_role_changed_publish_failed`, `targetUserId` |

All log lines include `correlationId` via existing MDC propagation.

---

## 9. Out of scope

Same as Section 1's OUT list.
