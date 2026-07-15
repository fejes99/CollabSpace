# Plan: Create Workspace

**Service:** auth-workspace
**Tier:** Full
**Slug:** create-workspace

---

## 1. Slice statement

Allow users to create new workspaces.

**IN**
- New endpoint
- New DB table
- Ability to create a new workspace for an authenticated user

**OUT**
- Don't edit existing endpoints
- Don't change infrastructure
- Don't change the authorization flow
- No duplicate/idempotency protection for double-submit; relies on future `X-Idempotency-Key` (v1.5, `api-conventions.md`)
- FK-violation race where the JWT's `userId` no longer exists (user deleted mid-session, token not yet expired/blocklisted) is not handled. Deferred until user deletion itself is implemented — at that point, either cascade-delete workspaces where the deleted user is sole admin, or block deletion while they hold admin roles, needs to be designed alongside it.
- Unknown-field rejection on request bodies (`FAIL_ON_UNKNOWN_PROPERTIES`) is not configured anywhere in this service, including on existing `RegisterRequest`/`LoginRequest`. Pre-existing, service-wide gap — not fixed in this branch. Flagged as a separate follow-up (global Jackson config, not a per-DTO change).

---

## 2. User-visible behavior

- A new endpoint allows an authenticated user to create a workspace.
- The user who created the workspace is returned as its `admin` in the response.

---

## 3. API contract

**`POST /v1/workspaces`**

**Auth:** Bearer JWT required. Any authenticated user may call this — workspace creation is not role-gated (`authorization.md`). Per ADR-032 (status: Proposed — this plan builds against it as-is; promoting it to Accepted is expected to follow once this ships and is verified on AWS, mirroring ADR-031's precedent), this is a self-directed membership change (creator == affected user), so the response reissues a fresh access token re-derived from a live DB read of current memberships, rather than waiting for natural token expiry.

The **original access token remains valid and is never blocklisted.** It's stale (missing the new membership claim), not invalid — it still grants everything it did before, until natural 15-minute expiry. Blocklisting it would revoke every capability the user already had (including concurrent in-flight requests using the same token) just because they created a workspace — this must not be implemented as a "reissue = revoke old" pattern.

**Request body**
```json
{ "name": "Engineering", "description": "Team workspace for engineering" }
```
`name` required. `description` optional.

**Response body (201)**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "workspace": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "name": "Engineering",
    "description": "Team workspace for engineering",
    "createdByUserId": "3fa85f64-...",
    "createdAt": "2026-07-13T10:00:00Z",
    "updatedAt": "2026-07-13T10:00:00Z"
  },
  "role": "admin"
}
```
`workspace.id` is a plain `UUID` string, matching `users.id`/`UserSummary.id` — no ID prefix scheme.

**Naming (per existing Register/Login precedent, Verb+Noun+Suffix):**
- REST adapter layer (`adapter/in/rest/workspace/`): `CreateWorkspaceRequest`, `CreateWorkspaceResponse`
- Application layer (`application/port/in/workspace/`): `CreateWorkspaceCommand`, `CreateWorkspaceResult`

**Status codes**
| Code | Scenario |
|---|---|
| 201 | Created |
| 400 | Validation failure (blank/oversized `name`, oversized `description`, malformed body/content-type) |
| 401 | Missing/invalid JWT |

No `409` (no unique constraint on name). No `422` (no business invariant applies to creation — the last-admin invariant only applies to removal/demotion).

**Transactionality and ordering (implementation-critical):**
1. Insert `workspaces` row and its first `workspace_memberships` row (role=`admin`) in a **single DB transaction** — both succeed or neither does. Prevents an orphaned workspace with zero admins if the membership insert fails independently.
2. **Commit the transaction before minting the new access token.** Never mint from claims that haven't been durably committed yet — mirrors ADR-032's own "DB commit → mint token" ordering rule. If signing fails after a successful commit (only plausible if the signing key itself is broken — a service-wide, not per-request, failure), this is a plain unhandled `500`; no bespoke recovery logic. The workspace is still correctly persisted in that case.

---

## 4. Data model changes

Single migration: `V4__create_workspaces_and_memberships.sql`

**`workspaces`**
| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `name` | `VARCHAR(255)` | NOT NULL |
| `description` | `TEXT` | nullable, app-layer max 2000 chars |
| `created_by_user_id` | `UUID` | NOT NULL, `REFERENCES users(id)` |
| `created_at` | `TIMESTAMPTZ` | NOT NULL |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL |

**`workspace_memberships`**
| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `workspace_id` | `UUID` | NOT NULL, `REFERENCES workspaces(id) ON DELETE CASCADE` |
| `user_id` | `UUID` | NOT NULL, `REFERENCES users(id) ON DELETE CASCADE` |
| `role` | `VARCHAR(20)` | NOT NULL, named `CHECK (role IN ('admin', 'member'))` |
| `created_at` | `TIMESTAMPTZ` | NOT NULL |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL (anticipates future role-change PR) |

Named `UNIQUE (workspace_id, user_id)` constraint — prevents duplicate membership row for the same user in the same workspace.

No `roles` lookup table — the two-role model is fixed for v1 per `authorization.md`; a `CHECK` constraint plus a Java `enum` (`@Enumerated(EnumType.STRING)`) is sufficient and avoids unnecessary abstraction.

---

## 5. Validation rules

| Field | Constraint | Error code |
|---|---|---|
| `name` | required; trim leading/trailing whitespace; non-blank after trim; max 255 chars | 400 |
| `description` | optional; max 2000 chars | 400 |

Explicitly out of scope for this slice: control-character filtering, XSS/HTML sanitization (belongs at output/render layer, not input), Unicode normalization, per-user workspace creation rate limiting, unknown-field rejection (see OUT list).

---

## 6. Edge cases

| Scenario | Expected response |
|---|---|
| Missing/blank `name` | 400, validation error listing `name` |
| `name` > 255 chars | 400, validation error listing `name` |
| `description` > 2000 chars | 400, validation error listing `description` |
| Missing/invalid JWT | 401 |
| Malformed JSON body / wrong content-type | 400 |
| Double-submit (two requests, identical `name`/`description`, same user) | Both succeed; two separate workspaces are created (accepted limitation — see OUT) |
| Membership insert fails after workspace insert (transient error) | Whole transaction rolls back — no orphaned workspace (see Section 3, Transactionality) |
| Token signing fails after successful DB commit | Plain 500; workspace remains correctly persisted (see Section 3, Transactionality) |

No "wrong role" case — creation is not role-gated. No "not found" case — this endpoint doesn't reference an existing resource by path.

---

## 7. Authorization

Any authenticated user (valid, non-blocklisted JWT) may call this endpoint. No workspace membership or role check applies — `authorization.md` states workspace creation is "not role-gated — it is a user-level action."

- Unauthenticated (missing/invalid JWT): 401 Unauthorized.
- No wrong-role case exists for this endpoint.
- The pre-existing access token used to authenticate this request is never blocklisted as a side effect of this endpoint (see Section 3).

---

## 8. Observability

**Audit event** (new row to be added to the table in `authentication.md` as part of this PR's polish phase):

| Event | Log fields |
|---|---|
| Workspace created | `userId`, `workspaceId`, `name`, `ip`, `jti` (new token), `correlationId` |

`name` is included un-hashed — unlike `email`, a workspace name isn't PII.

**Log lines:** one INFO-level line on successful creation with the fields above.

**Correlation ID:** standard service-wide propagation (read `X-Correlation-ID`, attach to MDC, echo in response) — no new logic needed.

---

## 9. Out of scope

Same as Section 1's OUT list.

---

## Follow-ups (not part of this PR)

- ADR-032: add a concrete number for the TTL "buffer" (currently unspecified — e.g. "+2 minutes"), and note the narrow, self-healing race between a self-directed reissue and a concurrent other-directed marker bump on the same user (unnecessary 401 + refresh-retry, not a security issue).
- ADR-032 status: promote from "Proposed" to "Accepted" once this feature ships and is verified on AWS.
- `coding-standards.md`: document the Verb+Noun+Suffix naming convention for Request/Response/Command/Result types (currently established in practice but undocumented).
- Global Jackson `FAIL_ON_UNKNOWN_PROPERTIES` config — pre-existing gap affecting all endpoints, not just this one.
