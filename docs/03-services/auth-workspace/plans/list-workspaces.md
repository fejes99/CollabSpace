# Plan: List Workspaces

**Branch:** `feat/auth/list-workspaces`
**Service:** auth-workspace
**Tier:** Full — cursor pagination is non-trivial, and this is the first implementation of `api-conventions.md`'s pagination convention anywhere in the codebase; getting the reference implementation right matters more than usual since later paginated endpoints (documents, etc.) will copy this pattern.

---

## 1. Slice statement

An authenticated user can retrieve a cursor-paginated list of every workspace they are a member of, including their role in each and that workspace's current member count.

**IN**
- New endpoint `GET /v1/workspaces` — any authenticated user, self-scoped (returns only the caller's own memberships; not role-gated, mirroring workspace creation).
- Cursor-based pagination following `api-conventions.md`'s standard shape exactly (`?limit=&after=`, opaque Base64-JSON cursor, `{ data, pagination: { hasNextPage, nextCursor, limit, count } }`) — this is the first endpoint to implement that convention in code. (`api-conventions.md`'s own worked example, `GET /v1/workspaces/{workspaceId}/documents?limit=20&after=<cursor>`, is a *different*, not-yet-built endpoint — this slice implements the same convention, not that example literally.)
- Each item returns `id`, `name`, the caller's `role`, and `memberCount` for that workspace.

**OUT**
- Sorting or filtering by anything other than the default join order (by name, by role, etc.) — v1.5.
- `description` in the list payload — kept light on purpose; a future `GET /v1/workspaces/{workspaceId}` detail endpoint (not yet built, not part of this slice) is the place for full workspace detail.
- HMAC-signed cursors — already deferred to v1.5 by `api-conventions.md`.

---

## 2. User-visible behavior

- An authenticated caller receives `200` with every workspace they belong to, each showing their role and current member count.
- Results are ordered oldest-membership-first (ascending join time); pagination position is stable across concurrent inserts/removals happening elsewhere.
- A caller with zero memberships receives `200` with an empty `data` array and `hasNextPage: false`.
- `limit` defaults to 20, caps at 100 (per `api-conventions.md`).
- A malformed or invalid `after` cursor is rejected with `400`, not silently ignored or reset to page one.

---

## 3. API contract

**Path:** `GET /v1/workspaces?limit={int}&after={cursor}`
**Auth:** Bearer JWT required. Self-scoped — no `@PreAuthorize`/`hasWorkspaceRole` check, since there is no target `workspaceId` in the path to check a role against. The query itself is filtered by the caller's own `userId` from `SecurityContextHolder`; there is no `userId` query parameter, and none should ever be added — accepting a caller-supplied `userId` would turn a self-scoped endpoint into a cross-user membership-enumeration hole.

Cursors are unsigned Base64 JSON (per `api-conventions.md` — no HMAC), so a caller could in principle copy `joinedAt`/`membershipId` values out of another user's response and paste them into their own `after` param. This is harmless: `userId` in the `WHERE` clause always comes from the authenticated principal, never from the cursor, so a forged cursor only changes *where in the caller's own list* the query resumes — it cannot make the query return another user's rows.

**Request body:** None.

**Response body (happy path, `200`):**

```json
{
  "data": [
    { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "name": "Acme Corp", "role": "admin", "memberCount": 4 }
  ],
  "pagination": {
    "hasNextPage": true,
    "nextCursor": "eyJqb2luZWRBdCI6IjIwMjYtMDQtMTVUMTA6MzI6MDBaIiwibWVtYmVyc2hpcElkIjoiN2M5ZTZmZTAtYzMwNS00MDBjLTk4M2YtOWEwZjRlOWQ4ZjRkIn0=",
    "limit": 20,
    "count": 20
  }
}
```

`id` is a bare UUID (no `ws:`-style prefix) — matching `WorkspaceSummary`, the existing response DTO's convention, not `api-conventions.md`'s illustrative example. `role` is the lowercase string form of `WorkspaceRole` (`"admin"` | `"member"`), matching the JWT `memberships` claim and the DB `role` column exactly.

**Status codes**

| Code | Scenario |
|---|---|
| 200 | Success (including an empty list) |
| 400 | `limit` out of range (`validation/invalid-request`, field `limit`) or `after` malformed/invalid (`validation/invalid-cursor`) |
| 401 | Missing/invalid JWT |

No `403` — self-scoped endpoints have no "wrong role" outcome. No `404` — this is a collection endpoint, not a single resource.

---

## 4. Data model changes

None — no migration. Reuses `workspace_memberships` (`V4__create_workspaces_and_memberships.sql`) and `workspaces` exactly as they stand. `workspace_memberships.created_at` (populated by `@CreatedDate` on insert, `updatable = false`) is the "joined at" timestamp — no new column needed.

**New repository method** (the non-trivial part of this slice): `WorkspaceMembershipRepository` needs a keyset-paginated query returning, per row, the membership's workspace id, name, the caller's role, and a live member count for that workspace — none of which exists today (`findByUserId` returns an unpaginated `List<WorkspaceMembership>` with no workspace name or count attached).

Proposed shape: a single JPQL query joining `WorkspaceMembershipEntity` → `WorkspaceEntity`, with `memberCount` computed by a correlated subquery, projected directly into a DTO via a constructor expression (avoids N+1 — one query per page, not one query per membership row):

```java
@Query("""
    SELECT new com.collabspace.authworkspace.adapter.out.persistence.workspace.WorkspaceListRow(
        w.id, w.name, m.role, m.createdAt, m.id,
        (SELECT COUNT(m2) FROM WorkspaceMembershipEntity m2 WHERE m2.workspaceId = w.id)
    )
    FROM WorkspaceMembershipEntity m JOIN WorkspaceEntity w ON w.id = m.workspaceId
    WHERE m.userId = :userId
      AND (m.createdAt > :afterCreatedAt
           OR (m.createdAt = :afterCreatedAt AND m.id > :afterMembershipId))
    ORDER BY m.createdAt ASC, m.id ASC
    """)
List<WorkspaceListRow> findPageForUser(UUID userId, Instant afterCreatedAt, UUID afterMembershipId, Limit limitPlusOne);
```

Fetch `limit + 1` rows; if the extra row comes back, `hasNextPage = true` and it's trimmed before building the response — this avoids a second `COUNT` query just to know whether another page exists. First page (`after` absent) uses `afterCreatedAt = Instant.EPOCH`, `afterMembershipId` a nil/minimum UUID, so the same query serves both first and subsequent pages without a branch.

**Use Spring Data's `Limit` type (`Limit.of(limit + 1)`), not `Pageable`.** `Pageable` is an offset-pagination abstraction — passed to a `@Query` method it appends `LIMIT`/**`OFFSET`** derived from page number × size. `PageRequest.of(0, limit + 1)` happens to produce `OFFSET 0` today, but it puts an offset-capable type in the signature of an endpoint whose entire point is avoiding offset pagination — the first future change that passes a non-zero page number silently reintroduces the scan-cost and consistency problems this design exists to avoid. `Limit` (Spring Data 3.2+) expresses "just a row cap," nothing else, and can't be misused that way.

**Caveat on the "avoids N+1" claim above:** it holds for the membership↔workspace join (one query total, not one per row), but the correlated `memberCount` subquery still runs once per returned row at the database level — up to `limit + 1` `COUNT`s per request, not one. That's `N+1`-shaped for the count specifically, just folded into a single round trip instead of the application making N extra queries. At this project's scale (free-tier, low traffic) this is a non-issue; noted so it isn't silently assumed to be fully solved.

**No new index.** The only index on `workspace_memberships` is `idx_workspace_memberships_user_id` (`user_id` alone, from `V4__create_workspaces_and_memberships.sql`) — it narrows to the caller's rows but doesn't cover `ORDER BY created_at, id`, so Postgres sorts the matched rows after the index scan rather than walking them pre-sorted. A composite index (`user_id, created_at, id`) would fix that. Deliberately deferred, not an oversight: free-tier traffic makes the extra sort a non-issue today, and this plan already declares "no migration" in its scope — but noted explicitly since this endpoint is meant to be the reference implementation for the pagination convention, and a future paginated endpoint under real load should not silently inherit "skip the covering index" as the copied pattern.

Cursor payload: `{ "joinedAt": "<ISO-8601 instant>", "membershipId": "<UUID>" }` — matches the keyset columns exactly, per `api-conventions.md`'s "cursor encodes the values needed to resume the query at the correct position."

---

## 5. Validation rules

No request body. Query parameters only:

| Field | Constraint | Error |
|---|---|---|
| `limit` | Optional. Must parse as an integer; `400 validation/invalid-request` (field `limit`, "must be an integer") if it doesn't (e.g. `?limit=abc`) — a distinct failure mode from being out of range. | `400`, `validation/invalid-request`, field `limit` |
| `limit` | If it parses: `1`–`100` inclusive. Default `20` if absent. | `400`, `validation/invalid-request`, field `limit` |
| `after` | Optional string. If present: must Base64-decode to a JSON object with `joinedAt` (valid ISO-8601 instant) and `membershipId` (valid UUID) — both fields present *and* of the correct type, not just present. A field that's missing, wrong-typed (e.g. `membershipId: 123`), or fails to parse as its target type all map to the same `invalid-cursor` error. | `400`, `validation/invalid-cursor` |

Rejecting an out-of-range `limit` outright (rather than silently clamping to 100) is a judgment call, not something `api-conventions.md` specifies either way — flagged for your review.

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| Caller has zero memberships | 200 | `data: []`, `hasNextPage: false`, `nextCursor: null` |
| `limit` omitted | 200 | defaults to 20 |
| `limit` = 0 or negative | 400 | `validation/invalid-request` |
| `limit` > 100 | 400 | rejected, not clamped (see §5 note) |
| `after` present but not valid Base64 / not valid JSON | 400 | `validation/invalid-cursor` |
| `after` JSON parses but a field is the wrong type or missing (e.g. `membershipId` is a number, or `joinedAt` is absent) | 400 | `validation/invalid-cursor` — same error as malformed, not a separate case |
| `after` well-formed but doesn't correspond to any real membership (future timestamp, random UUID) | 200 | not an error — per `api-conventions.md`, an unrecognized-but-valid cursor just resumes the keyset filter at that position, likely yielding an empty or short page |
| `after` points at a membership that was since removed (e.g., caller was removed and re-invited) | 200 | keyset filter only needs the two comparison values, not that the row still exists — behaves correctly regardless |
| Caller creates a new workspace while mid-pagination (fetches page 1, then creates a workspace, then fetches page 2) | 200 | the new membership's `created_at` is later than everything already paginated, so it lands on a page not yet fetched — standard cursor-pagination semantics, not a bug. Not retroactively inserted into a page already returned. |
| Exactly `limit` memberships remain after the cursor position (no more after this page) | 200 | `hasNextPage: false`, `count` equals `limit` — the off-by-one case for the "fetch `limit+1`, trim if the extra row shows up" pattern; worth its own test specifically because it's where that trick is easiest to get backwards |
| A workspace's member count changes between page 1 and page 2 being fetched | 200 | `memberCount` reflects the live count at query time for whichever page is being served — expected cursor-pagination behavior, not a bug, and not something already-delivered pages retroactively correct |
| Unauthenticated | 401 | existing filter chain, no new work |
| Every workspace has `memberCount >= 1` | — | structural guarantee, not a case to test explicitly — the last-admin invariant (ADR-038) means a workspace can never reach zero members via role/removal endpoints |

---

## 7. Authorization

- Caller must be authenticated (valid, non-blocklisted, non-stale JWT) — existing filter chain, unchanged.
- No role check — every authenticated user is entitled to see the list of workspaces *they* belong to, exactly as `authorization.md` treats workspace creation as a user-level action rather than a role-gated one.
- The security-critical property here isn't a role check at all — it's that the underlying query is *always* scoped to `SecurityContextHolder`'s current user, with no client-controllable `userId` parameter. Get that wrong and this becomes a way to enumerate any user's workspace membership.
- No distinction between self- and other-directed anything — there is no "other" in a self-scoped list endpoint.

---

## 8. Observability

**No new audit-event table row.** `authentication.md`'s audit-events table (Registration, Login, Token refresh, Logout, Blocklist check failure, Workspace created, Member role changed, Member removed) covers state-changing actions only — this endpoint is a pure read, consistent with that table having no row for, say, `GET /v1/workspaces/{id}/members` either (which doesn't exist yet, but establishes the precedent this endpoint would follow).

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful list | INFO | `event=workspaces_listed userId={} count={} limit={} hasNextPage={} correlationId={}` |
| Invalid `limit` | WARN | `event=list_workspaces_rejected reason=invalid_limit` |
| Invalid `after` cursor | WARN | `event=list_workspaces_rejected reason=invalid_cursor` |

**Revision history on this line, since it flip-flopped twice:** v1 had no success log line at all (reasoning: high-traffic non-mutating read, avoid volume). Round 2 review pointed out that leaves no way to debug a "missing workspace" report without reproducing it live, and tried DEBUG as a compromise — but `logback-spring.xml`'s `<root level="INFO">` means DEBUG lines are never emitted in any environment this service currently runs in, so that "fix" was silently inert. Landed on INFO: this project runs at free-tier/low-traffic scale (per CLAUDE.md), so the volume concern that motivated dropping the line in the first place doesn't actually apply here — it would apply to a production system at real scale, which this isn't (yet). Revisit if traffic ever grows enough for this to be a real cost/noise problem.

**Correlation ID:** propagated via existing MDC (`CorrelationIdFilter`) — no new work.

---

## 9. Out of scope

- Sorting or filtering by anything other than default join order (by name, by role) — v1.5.
- `description` in the list payload — reserved for a future workspace-detail endpoint.
- HMAC-signed cursors — already deferred to v1.5 by `api-conventions.md`.
