# Plan: Invite Member

**Branch:** `feat/auth/invite-member`
**Service:** auth-workspace
**Tier:** Full

---

## 1. Slice statement

Allow inviting a user to a workspace by email.

**IN**
- Add new endpoint (`POST /v1/workspaces/{id}/members`)
- Add service method with tests
- Publish a domain event for future integration (Notification service)
- Write `membership-changed-at:<invitedUserId>` marker in Redis (other-directed change, ADR-032)
- Add read-side check to the security filter chain: compare `membership-changed-at:<userId>` against the token's `iat`, reject `401 claims-stale` on a stale request (completes the item deferred in `security-filter.md` §9)

**OUT**
- Listing workspaces
- Pending invites
- Removing users from a workspace
- Changing an existing member's role (member → admin or vice versa) — this endpoint `409`s if the target email is already a member, regardless of requested `role`; it never mutates an existing membership row. Promotion/demotion is the separate "Change member role" capability (`authorization.md`), tracked in `notes/auth-workspace-prs.md`

---

## 2. User-visible behavior

- Admin caller receives `201 Created` with the created membership.
- Non-admin caller receives `403 Forbidden`.
- Unauthenticated caller receives `401 Unauthorized`.
- Inviting an email with no matching registered user receives `404 Not Found`.
- Inviting a user who is already a member of the workspace receives `409 Conflict`.
- The invited user's *next* authenticated request anywhere, if made with a token issued before the invite, is rejected `401 claims-stale` — their client transparently refreshes and retries, after which their token reflects the new membership.

---

## 3. API contract

**Path:** `POST /v1/workspaces/{workspaceId}/members`
**Auth:** Bearer JWT required. Caller must hold `admin` role in `{workspaceId}` (`@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")` — see §7, this PR builds `hasWorkspaceRole`).

**Request body**
```json
{ "email": "user@example.com", "role": "member" }
```
`email` required. `role` optional — `admin` or `member`, defaults to `member` if omitted.

**Response body (201)**
```json
{
  "invitedUserId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "user@example.com",
  "role": "member",
  "workspaceId": "3fa85f64-...",
  "joinedAt": "2026-07-15T10:00:00Z"
}
```
`invitedUserId` names the field unambiguously — this response represents the created membership, and without the `invited` prefix it would be unclear whether `userId` referred to the invited user or the calling admin.

**Status codes**
| Code | Scenario |
|---|---|
| 201 | Created |
| 400 | Validation failure (`email` malformed, `role` not `admin`/`member`) |
| 401 | Missing/invalid JWT |
| 403 | Caller not a member of the workspace (`authorization/not-a-member`), or member but not admin (`authorization/insufficient-role`) |
| 404 | No registered user with that email (`workspace/user-not-found`) |
| 409 | Target user already a member of this workspace (`workspace/already-member`) |

**Transactionality and ordering (implementation-critical):** reuse `CommitThenAction` (ADR-034) rather than re-deriving the pattern — `writes` = insert the `workspace_memberships` row; `afterCommit` = write the `membership-changed-at:<invitedUserId>` Redis marker and publish the `member.invited` SNS event. Both steps only run once the membership row is durably committed, so a failed insert never produces a marker or event for a membership that doesn't exist.

**`afterCommit` exception isolation:** `CommitThenAction`'s `Supplier<T> afterCommit` was designed around ADR-034's single-step case (mint a token). This PR needs two independent, independently-fail-open steps inside it — the Redis marker write and the SNS publish must each be wrapped in their own try/catch *inside* the `afterCommit` lambda, logging and swallowing failures individually. Without this, an exception from the Redis write would propagate past the SNS publish (never attempted) and past `CommitThenAction.run()` itself, turning two steps that should independently fail-open into an unhandled `500` — exactly the "second use case with different failure semantics" ADR-034's Consequences section flagged as the reshape trigger. This PR keeps `CommitThenAction`'s existing shape and handles the isolation inside the lambda rather than reshaping the class — revisit if a third caller needs the same isolation, at which point pushing this into `CommitThenAction` itself becomes worth it.

**Email lookup normalization:** the invite email is lowercased in the service layer before the `users` lookup, mirroring `AuthApplicationService`'s existing normalization for registration (`user-registration.md` §5) — `users.email` is stored lowercased, so an unnormalized lookup would silently 404 on any mixed-case input.

**Malformed `workspaceId`:** if the path segment isn't a valid UUID, return `400` (standard `MethodArgumentTypeMismatchException` handling), not a fall-through to `403`/`404`.

---

## 4. Data model changes

None. Reuses the existing `workspace_memberships` table (`workspace_id`, `user_id`, `role` with its `admin`/`member` CHECK constraint) and `users`/`workspaces` tables from `V4__create_workspaces_and_memberships.sql`. Inserts one new `workspace_memberships` row.

---

## 5. Validation rules

| Field | Constraint | Error |
|---|---|---|
| `email` | `@NotBlank`, `@Email`, `@Size(max = 254)` — same as registration. Normalized (lowercased) in the service layer before the `users` lookup, same as registration's own email normalization | 400 + `errors` array |
| `role` | optional; validated at the DTO boundary via a custom `@ValidRole` constraint (`RoleValidator`), not a built-in annotation — `null` passes (role is optional, defaulting is a separate concern from validity), any non-null value is checked by delegating to `WorkspaceRole.fromString(value)`. Absent → defaults to `member` | 400 + `errors` array |

**`@ValidRole` / `WorkspaceRole.fromString`:** `WorkspaceRole.fromString` (already existing, used today by `WorkspaceRoleConverter` to read the DB column) is trim + case-insensitive tolerant, iterating `WorkspaceRole.values()` rather than a hardcoded literal set — so a future third role needs no changes to either the validator or `fromString`. `RoleValidator` delegates directly to `fromString` (try/catch on `IllegalArgumentException`) instead of re-implementing the same normalization logic, so the DTO-boundary check and the actual string→enum conversion can never disagree with each other. Both live in `adapter/in/rest/workspace/` alongside `InviteMemberRequest` — no new subpackage, following this codebase's existing precedent of staying flat until a package actually grows past ~10 files (`security-filter.md`'s package-reorg note).

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| Missing/blank `email` | 400 | |
| Invalid email format | 400 | |
| `email` > 254 chars | 400 | |
| Mixed-case email (e.g. `Bob@Company.com`) matching a registered user | 201 | lookup normalizes to lowercase before querying `users` — see §3 |
| `role` present but not `admin`/`member` after normalization | 400 | |
| Missing request body | 400 | |
| Malformed `workspaceId` path segment (not a valid UUID) | 400 | see §3 |
| Unauthenticated (missing/invalid JWT) | 401 | |
| Caller not a member of `{workspaceId}` (including a nonexistent workspace) | 403, `not-a-member` | masks workspace existence, per `authorization.md`'s membership-visibility rule |
| Caller is a member but not admin | 403, `insufficient-role` | |
| Email doesn't match any registered user | 404 | |
| Target user already a member of this workspace | 409 | includes an admin "inviting" themselves — already a member, same 409 path, no special-casing |
| Redis `membership-changed-at` marker write fails | 201 | fail open, log ERROR (`event=membership_marker_write_failed`) — isolated from the SNS publish step, see §3 |
| `member.invited` SNS publish fails | 201 | fail open, log ERROR (`event=member_invited_publish_failed`) — see Follow-ups |
| No `membership-changed-at:<userId>` marker exists for a user (never had an other-directed change) | passes | no comparison possible — mirrors the existing "absent `jti` → passes" rule in `security-filter.md` §7 |
| Stale token used on a later request by the invited user (`iat` < `membership-changed-at`) | 401, `claims-stale` | new read-side check this PR adds to the filter chain |
| Self-directed token reissue (e.g. the same user creates a workspace) lands concurrently with an other-directed marker bump on that same user | 401, `claims-stale`, once | known, accepted, self-healing race flagged in `create-workspace.md`'s Follow-ups — client's normal refresh-retry absorbs it; first PR where this race becomes reachable instead of theoretical |

---

## 7. Authorization

- Caller must be authenticated (valid, non-blocklisted JWT) — enforced by the existing PR 7 filter chain.
- Caller must hold `admin` role in `{workspaceId}` — enforced via `@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`. This PR builds `hasWorkspaceRole` itself (a custom Spring Security method-security expression reading the `memberships` array off the `Authentication` principal), correcting the stale assumption in `security-filter.md` §9 that PR 8 would build it — PR 8 (`create-workspace`) is not role-gated and never needed it.
- Unauthenticated: `401`.
- Authenticated, not a member of the workspace: `403 not-a-member`.
- Authenticated, member but not admin: `403 insufficient-role`.
- Target-already-a-member is a business invariant, not authorization: `409`, handled at the service layer, per `authorization.md`'s authorization-vs-invariant distinction.

**Distinguishing the two `403` types:** a plain boolean `@PreAuthorize` expression can't communicate *why* it returned `false`, and `security-filter.md` §4 confirms all `AccessDeniedException`s route through one shared `ProblemDetailsSecurityHandler.accessDeniedHandler`. To still produce two distinct Problem Details bodies, `hasWorkspaceRole`'s backing implementation throws one of two custom exceptions instead of returning `false`: `NotAMemberException` → `authorization/not-a-member` (no membership entry for the workspace) or `InsufficientRoleException` → `authorization/insufficient-role` (membership exists, wrong role) — both already-reserved types in `error-catalog.md`, both extending Spring Security's `AccessDeniedException` and each declaring its own `TYPE` URI, following the same `getType()` pattern the existing `AuthenticationException` subtypes use (`security-filter.md` §4). Since both still extend `AccessDeniedException`, `ExceptionTranslationFilter` routes them to the existing shared handler with no new wiring — the handler just needs to call `ex.getType()` polymorphically instead of hardcoding one type, mirroring how it already handles multiple `AuthenticationException` subtypes.

**Testing/documentation bar for `hasWorkspaceRole`:** mutation-tested unit coverage, matching the standard `security-filter.md` §8 set for the PR 7 filters. Minimum cases: caller has the required role in the target workspace (pass); caller has a different role in the target workspace (fail); caller has the required role in a *different* workspace only (fail — proves workspace-scoping, not just role-string matching); caller has no membership at all (fail); malformed/empty authorities (fail closed). Add a doc comment explaining why it reads from the `Authentication` principal rather than querying the DB (stateless-JWT design, per `authorization.md`).

---

## 8. Observability

**Audit event** (new row added to `authentication.md`'s table as part of this PR):

| Event | Log fields |
|---|---|
| Member invited | `userId` (inviting admin), `invitedUserId`, `emailHash` (SHA-256), `workspaceId`, `role`, `ip`, `correlationId` |

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful invite | INFO | `event=member_invited` |
| Caller not a member | WARN | `event=member_invite_rejected`, `reason=not_a_member` |
| Caller not admin | WARN | `event=member_invite_rejected`, `reason=insufficient_role` |
| Email not found | WARN | `event=member_invite_rejected`, `reason=user_not_found`, `emailHash` |
| Already a member | WARN | `event=member_invite_rejected`, `reason=already_member`, `invitedUserId` |
| Redis marker write fails | ERROR | `event=membership_marker_write_failed`, `invitedUserId` |
| SNS publish fails | ERROR | `event=member_invited_publish_failed`, `invitedUserId` |
| Claims-stale rejection (new filter read-side check) | WARN | `event=claims_stale_rejected`, `userId`, `jti` — mirrors `JwtBlocklistFilter`'s `blocklist_check_failed` |

All log lines include `correlationId` via the existing MDC propagation.

**Never log:** plaintext email.

---

## 9. Out of scope

Same as Section 1's OUT list.

---

## Follow-ups (not part of this PR)

- **Outbox pattern for `member.invited`.** Currently a direct synchronous `SnsClient.publish()` call, fail-open on failure — if the publish fails or is lost, the event is gone forever. No consumer exists yet so the immediate impact is zero, but a transactional outbox (write the event to a DB table in the same transaction as the membership insert, a separate poller publishes with retry) is the standard fix once a real consumer (Notification Lambda) depends on this event arriving. Tracked in `notes/auth-workspace-prs.md`.
- **Broader resilience-patterns learning goal** (timeouts, retry+backoff, circuit breaker, bulkhead, fallback, idempotent consumers, dead-letter queues) — flagged during this PR's planning, tracked in `notes/auth-workspace-prs.md`.
- **`authentication.md` correction.** §"Membership and role change invalidation" lists "accepts an invite" as an example of a *self-directed* change. This PR's actual design has no accept step — invites take effect immediately and are admin-directed, so they're other-directed instead. That doc's example list should be corrected once this PR ships, so a future reader isn't steered toward building a self-directed accept-invite flow that doesn't exist in this codebase.
- **`CommitThenAction` reshape.** This PR handles two independent fail-open post-commit steps by isolating them with try/catch inside its `afterCommit` lambda (see §3), keeping the class's existing single-`Supplier` shape. If a third caller needs the same multi-step isolation, that's the signal ADR-034 named to actually reshape `CommitThenAction` itself (e.g. accepting a list of independently-caught actions) rather than repeating the inline try/catch pattern a third time.
