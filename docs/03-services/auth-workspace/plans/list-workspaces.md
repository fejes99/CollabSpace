# Plan: List Workspaces

**Branch:** `feat/auth/list-workspaces`
**Service:** auth-workspace
**Tier:** Full — cursor pagination is non-trivial, and this is the first implementation of `api-conventions.md`'s pagination convention anywhere in the codebase; getting the reference implementation right matters more than usual since later paginated endpoints (documents, etc.) will copy this pattern.

**Revision note:** this plan originally described a self-scoped "workspaces I'm a member of" endpoint (v1–v3, adversarially reviewed twice). Revised here to a system-wide "list every workspace" endpoint instead, per a deliberate product-scope decision: this app isn't multi-tenant and workspaces aren't private, so a self-scoped list isn't the useful default — it's deferred to v1.5 (see §9). This revision changes the API contract, the query design, and — most importantly — the authorization stance, so treat this as a new plan built on the old one's scaffolding, not a patch.

---

## 1. Slice statement

Any authenticated user can retrieve a cursor-paginated list of every workspace in the system, with each workspace's current member count.

**IN**
- New endpoint `GET /v1/workspaces` — any authenticated user. Not scoped to the caller's own memberships — returns every workspace that exists, identically for every caller.
- Cursor-based pagination following `api-conventions.md`'s standard shape exactly (`?limit=&after=`, opaque Base64-JSON cursor, `{ data, pagination: { hasNextPage, nextCursor, limit, count } }`) — this is the first endpoint to implement that convention in code. (`api-conventions.md`'s own worked example, `GET /v1/workspaces/{workspaceId}/documents?limit=20&after=<cursor>`, is a *different*, not-yet-built endpoint — this slice implements the same convention, not that example literally.)
- Each item returns `id`, `name`, and `memberCount` for that workspace. No `role` field — role is a membership-level concept (a fact about *a specific user's* relationship to a workspace), and this endpoint operates at the workspace level, not scoped to any one caller's memberships. A field that would be `null` for most rows (every workspace the caller isn't in) isn't a better answer than not having the field.

**OUT**
- Self-scoped "workspaces I'm a member of" — a genuinely different, narrower slice, deferred to v1.5. When built, it's a separate endpoint or a query parameter on this one, not a retrofit of this plan.
- Full member roster per workspace (every member's identity, not just a count) — deliberately deferred. A list/browse view doesn't need it; a future per-workspace detail endpoint (`GET /v1/workspaces/{workspaceId}/members` — named as a capability in `authorization.md`'s roles table already, not yet built) is the right home for that, at the right granularity (one workspace at a time, not N nested rosters in one list response).
- `description` in the list payload — kept light on purpose; a future `GET /v1/workspaces/{workspaceId}` detail endpoint (not yet built, not part of this slice) is the place for full workspace detail.
- Sorting or filtering by anything other than the default creation order — v1.5.
- HMAC-signed cursors — already deferred to v1.5 by `api-conventions.md`.

---

## 2. User-visible behavior

- Any authenticated caller receives `200` with every workspace in the system, each showing its current member count. Membership in a workspace is not required to see it listed.
- Every authenticated caller sees the identical list and identical `memberCount` values — the response does not depend on who's asking, only on system-wide workspace state.
- Results are ordered oldest-workspace-first (ascending creation time); pagination position is stable across concurrent inserts/removals happening elsewhere.
- If no workspaces exist system-wide, `200` with an empty `data` array and `hasNextPage: false`.
- `limit` defaults to 20, caps at 100 (per `api-conventions.md`).
- A malformed or invalid `after` cursor is rejected with `400`, not silently ignored or reset to page one.

---

## 3. API contract

**Path:** `GET /v1/workspaces?limit={int}&after={cursor}`
**Auth:** Bearer JWT required. The caller must be authenticated, but — unlike every other endpoint in this service — authentication here answers only "is this a logged-in user," not "which specific user, for the purpose of scoping data." There is no `userId` query parameter, and none should ever be added — this endpoint has no per-user filtering to accept one into. The query is identical for every caller by design (see §7).

Cursors are unsigned Base64 JSON (per `api-conventions.md` — no HMAC). Unlike the self-scoped design this plan replaces, there's no cross-user forgery concern to reason about here at all: since the query isn't scoped to any caller's identity, there's no "another user's data" a forged cursor could expose — every cursor just resumes the same system-wide list at a different position, for anyone.

**Request body:** None.

**Response body (happy path, `200`):**

```json
{
  "data": [
    { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "name": "Acme Corp", "memberCount": 4 }
  ],
  "pagination": {
    "hasNextPage": true,
    "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2LTA0LTE1VDEwOjMyOjAwWiIsIndvcmtzcGFjZUlkIjoiN2M5ZTZmZTAtYzMwNS00MDBjLTk4M2YtOWEwZjRlOWQ4ZjRkIn0=",
    "limit": 20,
    "count": 20
  }
}
```

`id` is a bare UUID (no `ws:`-style prefix) — matching `WorkspaceSummary`, the existing response DTO's convention, not `api-conventions.md`'s illustrative example.

**Status codes**

| Code | Scenario |
|---|---|
| 200 | Success (including an empty list) |
| 400 | `limit` out of range (`validation/invalid-request`, field `limit`) or `after` malformed/invalid (`validation/invalid-cursor`) |
| 401 | Missing/invalid JWT |

No `403` — there's no role or membership gate on this endpoint at all. No `404` — this is a collection endpoint, not a single resource.

---

## 4. Data model changes

None — no migration. Reuses `workspaces` (`V4__create_workspaces_and_memberships.sql`) exactly as it stands. `workspaces.created_at` is the ordering key — no new column needed.

**New repository method** (the non-trivial part of this slice, and simpler than the self-scoped version it replaces): `WorkspaceRepository` needs a keyset-paginated query returning, per row, the workspace's id, name, and a live member count — none of which exists today (`WorkspaceRepository` currently only has `findById`/`save`). Unlike the self-scoped design, there's no join to `workspace_memberships` needed for filtering — only a correlated subquery for the count:

```java
@Query("""
    SELECT new com.collabspace.authworkspace.application.port.out.workspace.WorkspaceListRow(
        w.id, w.name, w.createdAt,
        (SELECT COUNT(m) FROM WorkspaceMembershipEntity m WHERE m.workspaceId = w.id)
    )
    FROM WorkspaceEntity w
    WHERE (w.createdAt > :afterCreatedAt
           OR (w.createdAt = :afterCreatedAt AND w.id > :afterWorkspaceId))
    ORDER BY w.createdAt ASC, w.id ASC
    """)
List<WorkspaceListRow> findPage(Instant afterCreatedAt, UUID afterWorkspaceId, Limit limit);
```

**Correction from the original draft of this section:** `WorkspaceListRow` belongs in `application/port/out/workspace/`, not `adapter/out/persistence/workspace/` as first sketched here. `WorkspaceRepository` (the port, in `application/port/out/workspace`) returns `List<WorkspaceListRow>` from `findPage`, and this codebase's own dependency rule — "nothing in `domain/` or `application/` imports from `adapter/`" — means the port can't reference a type that lives in the adapter layer. `WorkspaceMembershipRepository` already establishes the pattern of port-returned shapes living alongside the port interface itself, not in the adapter. The adapter's JPQL constructor expression can still target it fine — a `SELECT NEW` projection target doesn't need to be a JPA entity or live in the adapter package, it just needs a matching constructor.

The port method itself (`WorkspaceRepository.findPage`) takes a plain `int limit`, not a `Limit`/`Pageable` — matching `WorkspaceMembershipRepository`'s existing convention of never letting Spring Data types appear in a port signature. `Limit.of(limit)` is constructed only inside `WorkspaceJpaAdapter`, right before calling the Spring Data repository. The "+1 for `hasNextPage`" trick is the *caller's* choice of what `limit` to pass (e.g. `command.limit() + 1`), not something this method does on its own — keeps the repository a dumb "give me up to N rows after this position" primitive, with pagination-response semantics staying in the application service where the rest of this TODO already lives.

Fetch `limit + 1` rows (the caller's responsibility, per above); if the extra row comes back, `hasNextPage = true` and it's trimmed before building the response — avoids a second `COUNT` query just to know whether another page exists. First page (`after` absent) uses `afterCreatedAt = Instant.EPOCH`, `afterWorkspaceId` a nil/minimum UUID, so the same query serves both first and subsequent pages without a branch — resolved by the application service before calling this method, since `WorkspaceRepository`'s own signature takes plain, non-`Optional` values.

**`WorkspaceListRow` (this repository projection) and the §3 response item are not the same shape, on purpose.** The row carries `createdAt` because the application layer needs it to build `nextCursor` from the last row of the trimmed page — but `createdAt` never appears in the JSON response itself (§3 only has `id`/`name`/`memberCount`). The application-layer result type and the REST DTO both drop it after using it to compute `nextCursor`; only the adapter-out projection carries it at all.

**Use Spring Data's `Limit` type (`Limit.of(limit + 1)`), not `Pageable`.** `Pageable` is an offset-pagination abstraction — passed to a `@Query` method it appends `LIMIT`/**`OFFSET`** derived from page number × size. `PageRequest.of(0, limit + 1)` happens to produce `OFFSET 0` today, but it puts an offset-capable type in the signature of an endpoint whose entire point is avoiding offset pagination — the first future change that passes a non-zero page number silently reintroduces the scan-cost and consistency problems this design exists to avoid. `Limit` (Spring Data 3.2+) expresses "just a row cap," nothing else, and can't be misused that way.

**Caveat on the count subquery:** the correlated `memberCount` subquery runs once per returned row at the database level — up to `limit + 1` `COUNT`s per request, not one. That's `N+1`-shaped for the count specifically, just folded into a single round trip instead of the application making N extra queries. At this project's scale (free-tier, low traffic) this is a non-issue; noted so it isn't silently assumed to be fully solved.

**No new index.** `workspaces` has no index beyond its primary key today. `ORDER BY created_at, id` means Postgres sorts the matched rows after a full scan rather than walking them pre-sorted. A composite index (`created_at, id`) would fix that. Deliberately deferred, not an oversight: free-tier traffic makes the extra sort a non-issue today, and this plan already declares "no migration" in its scope — but noted explicitly since this endpoint is meant to be the reference implementation for the pagination convention, and a future paginated endpoint under real load should not silently inherit "skip the covering index" as the copied pattern.

Cursor payload: `{ "createdAt": "<ISO-8601 instant>", "workspaceId": "<UUID>" }` — matches the keyset columns exactly, per `api-conventions.md`'s "cursor encodes the values needed to resume the query at the correct position."

---

## 5. Validation rules

No request body. Query parameters only:

| Field | Constraint | Error |
|---|---|---|
| `limit` | Optional. Must parse as an integer; `400 validation/invalid-request` (field `limit`, "must be an integer") if it doesn't (e.g. `?limit=abc`) — a distinct failure mode from being out of range. | `400`, `validation/invalid-request`, field `limit` |
| `limit` | If it parses: `1`–`100` inclusive. Default `20` if absent. | `400`, `validation/invalid-request`, field `limit` |
| `after` | Optional string. If present: must Base64-decode to a JSON object with `createdAt` (valid ISO-8601 instant) and `workspaceId` (valid UUID) — both fields present *and* of the correct type, not just present. A field that's missing, wrong-typed (e.g. `workspaceId: 123`), or fails to parse as its target type all map to the same `invalid-cursor` error. | `400`, `validation/invalid-cursor` |

Rejecting an out-of-range `limit` outright (rather than silently clamping to 100) is a judgment call, not something `api-conventions.md` specifies either way — flagged for your review.

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| No workspaces exist system-wide | 200 | `data: []`, `hasNextPage: false`, `nextCursor: null` |
| `limit` omitted | 200 | defaults to 20 |
| `limit` = 0 or negative | 400 | `validation/invalid-request` |
| `limit` > 100 | 400 | rejected, not clamped (see §5 note) |
| `after` present but not valid Base64 / not valid JSON | 400 | `validation/invalid-cursor` |
| `after` JSON parses but a field is the wrong type or missing (e.g. `workspaceId` is a number, or `createdAt` is absent) | 400 | `validation/invalid-cursor` — same error as malformed, not a separate case |
| `after` well-formed but doesn't correspond to any real workspace (future timestamp, random UUID) | 200 | not an error — per `api-conventions.md`, an unrecognized-but-valid cursor just resumes the keyset filter at that position, likely yielding an empty or short page |
| `after` points at a workspace that was since deleted | 200 | keyset filter only needs the two comparison values, not that the row still exists — behaves correctly regardless. (Workspace deletion isn't built yet either way — see `remove-member.md`'s own OUT list.) |
| A new workspace is created while a caller is mid-pagination | 200 | its `created_at` is later than everything already paginated, so it lands on a page not yet fetched — standard cursor-pagination semantics, not a bug |
| Exactly `limit` workspaces remain after the cursor position (no more after this page) | 200 | `hasNextPage: false`, `count` equals `limit` — the off-by-one case for the "fetch `limit+1`, trim if the extra row shows up" pattern; worth its own test specifically because it's where that trick is easiest to get backwards |
| A workspace's member count changes between page 1 and page 2 being fetched | 200 | `memberCount` reflects the live count at query time for whichever page is being served — expected cursor-pagination behavior, not a bug |
| Unauthenticated | 401 | existing filter chain, no new work |
| Every workspace has `memberCount >= 1` | — | structural guarantee, not a case to test explicitly — the last-admin invariant (ADR-038) means a workspace can never reach zero members via role/removal endpoints |

---

## 7. Authorization

- Caller must be authenticated (valid, non-blocklisted, non-stale JWT) — existing filter chain, unchanged. This is still required; the endpoint is not public/unauthenticated.
- No role check, and — the material difference from every other endpoint in this service — **no per-user data scoping at all.** Every authenticated caller receives the identical result set. Authentication here gates *access to the API*, not *access to a subset of data*.
- **Why require auth at all, given the data itself carries no confidentiality?** Three reasons, none of which is "the data needs protecting": (1) consistency — every business endpoint in this service requires auth; the only public routes anywhere are infrastructure ones (`/actuator/health`, `.well-known/*`), and there's no precedent for a public *business* endpoint, so a carve-out here would be the odd one out, not the norm; (2) it keeps `userId` available for the §8 audit log line; (3) defense-in-depth — if workspace privacy is ever revisited, tightening this endpoint later is a service-logic change, not an API Gateway public-route change.
- **This is a deliberate, scoped exception to `authorization.md`'s existing masking principle, stated explicitly here since no ADR was written for it.** `authorization.md`'s § Authorization failure response states that a non-member gets `403 not-a-member` rather than `404`, specifically so workspace *existence* isn't leaked to someone without access — "the same principle as not revealing whether an email is registered." This endpoint treats a workspace's existence, name, and member count as information visible to any authenticated user, full stop — a conscious product decision (this app isn't multi-tenant, workspaces aren't private constructs), not an oversight. What's *not* changing: everything else `authorization.md` governs — a workspace's contents (documents), its member identities, and every mutating action (invite/remove/change-role/create) — remain exactly as membership- and role-gated as before. This endpoint only concerns the existence-level facts already listed above; it does not widen access to anything else.
- **Reconciling against `authorization.md`'s "no elevated cross-workspace access" line specifically** (§ Out of scope: *"There is no concept of a user having elevated access across all workspaces. There is no superadmin."*) — taken at face value, this endpoint is exactly a cross-workspace read, available identically to every user. The distinction is the same one drawn above for the masking principle: that sentence is about *permissions* (the ability to act across workspaces — invite, remove, change roles, read documents), not *visibility* (knowing a workspace exists and how many people are in it). No user gets elevated permissions anywhere from this endpoint; every mutating and content-reading capability stays exactly as gated as before. Named explicitly here so a future reader doesn't find this endpoint and conclude `authorization.md` was silently violated.
- No `role` field in the response (§1) means there's no membership-probing side channel here either — nothing in the payload reveals who's a member of what.
- **Consequence worth naming, not just accepting by default:** any authenticated user — including one with zero workspace memberships — can paginate through the entire system-wide workspace directory, indefinitely, with no rate limiting anywhere in this codebase today. Given the product's non-multi-tenant premise this is treated as an accepted tradeoff, not a gap to close in this slice, but it's a direct consequence of this design decision and is recorded here as such rather than left to be discovered later.

---

## 8. Observability

**No new audit-event table row.** `authentication.md`'s audit-events table (Registration, Login, Token refresh, Logout, Blocklist check failure, Workspace created, Member role changed, Member removed) covers state-changing actions only — this endpoint is a pure read.

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful list | INFO | `event=workspaces_listed userId={} count={} limit={} hasNextPage={} correlationId={}` — `userId` here identifies *who called the endpoint*, for audit-trail purposes; it plays no role in what data comes back (§7) |
| Invalid `limit` | WARN | `event=list_workspaces_rejected reason=invalid_limit` |
| Invalid `after` cursor | WARN | `event=list_workspaces_rejected reason=invalid_cursor` |

INFO, not DEBUG: this project runs at free-tier/low-traffic scale (per CLAUDE.md), so the volume concern that would motivate a lower log level in a high-traffic production system doesn't apply here — and `logback-spring.xml`'s `<root level="INFO">` means DEBUG lines wouldn't be emitted in any environment this service currently runs in anyway.

**Correlation ID:** propagated via existing MDC (`CorrelationIdFilter`) — no new work.

---

## 9. Out of scope

- Self-scoped "workspaces I'm a member of" — deferred to v1.5. A genuinely different, narrower slice; not a filter parameter retrofitted onto this endpoint.
- Full member roster per workspace — deferred; `memberCount` only for now. A future per-workspace detail/members endpoint is the right home for full rosters, not this list endpoint.
- Sorting or filtering by anything other than default creation order — v1.5.
- `description` in the list payload — reserved for a future workspace-detail endpoint.
- HMAC-signed cursors — already deferred to v1.5 by `api-conventions.md`.
