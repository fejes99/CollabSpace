# Plan: Token Refresh

**Branch:** `feat/auth/token-refresh`
**Service:** auth-workspace
**Tier:** Full

---

## 1. Slice statement

Create a new endpoint that reissues a refresh token.

**IN**
- Reads the refresh token from the `refresh_token` cookie and looks it up in the `refresh_tokens` table.
- Atomically rotates it in a single DB transaction — deletes the old row, inserts a new refresh token row, and issues a new access token.
- Returns `401` if the cookie is missing, the token isn't found, or it's expired.

**OUT**
- No logout endpoint.
- No database schema change.
- No new SNS topics.

---

## 2. User-visible behavior

- A `POST /v1/auth/refresh` call with a valid, unexpired `refresh_token` cookie returns `200 OK` with a fresh `accessToken` in the body.
- The response sets a new `refresh_token` cookie (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/v1/auth`, `Max-Age=604800`) whose value differs from the one that was sent.
- The old refresh token cookie value stops working — a second call reusing it returns `401`.
- A call with no `refresh_token` cookie returns `401`.
- A call with an expired refresh token returns `401`.
- The new access token's `memberships` claim reflects the caller's current workspace memberships, re-derived from the database — not copied forward from the old token (required by `authentication.md` so stale-claims invalidation, ADR-032, actually works).
- **The caller's previously issued access token (the one whose session is being refreshed) is not revoked by this call** — it remains valid, exactly as any other unexpired access token would, until its own natural 15-minute expiry. Refresh only replaces the refresh token; it does not blocklist anything. This is the existing fat-JWT design (`authentication.md`), stated here explicitly so it isn't mistaken for an oversight during implementation or review.
- **Other active sessions for the same user (other devices/browsers, each with their own `refresh_tokens` row) are unaffected.** Rotation only deletes the single row matching the presented `token_hash` — a user logged in on two devices and refreshing on one does not get signed out of the other.

---

## 3. API contract

**Path:** `POST /v1/auth/refresh`
**Auth:** None — one of the three unauthenticated auth routes per `api-conventions.md`. Identity comes from the `refresh_token` cookie, not a JWT.
**Request body:** None.
**Response body (happy path):**
```json
{ "accessToken": "<jwt>" }
```
Plus `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/v1/auth; Max-Age=604800`.

**Status codes**

| Code | Scenario | `type` |
|---|---|---|
| 200 | Refresh succeeded — new access token issued, refresh token rotated | — |
| 401 | No `refresh_token` cookie present, or cookie present but hash not found in `refresh_tokens` | `auth/refresh-token-invalid` |
| 401 | Row found but `expires_at < now()` | `auth/refresh-token-expired` |

**Two distinct `401` types, not one.** "Missing/not-found" and "expired" are deliberately given different `type` values even though both are `401`:
- `refresh-token-invalid` covers "no cookie" and "cookie doesn't match any row" identically — there's no meaningful reason for a client to react differently to those two, and collapsing them avoids giving a probing caller feedback about whether a given cookie value came close to matching.
- `refresh-token-expired` is kept separate because it's a routine, non-adversarial event (a real session that naturally timed out) versus a potentially adversarial one (a stale/garbage/replayed token). The client can reasonably show a soft "please log in again" for the former and something more cautious for the latter, per `authentication.md`'s guidance that a rejected-but-not-expired refresh should be treated as a signal to force full re-authentication.

**Implementation note:** both `type` values are new — neither exists in `error-catalog.md` today. Add both rows to that catalog in the implementation PR (per the catalog's own rule: "add the row in the same PR that introduces the error"), under a new `auth/` entries block alongside the existing ones.

---

## 4. Data model changes

None — no migration needed. `refresh_tokens` already exists (`V3__create_refresh_tokens.sql`): `id`, `user_id`, `token_hash`, `created_at`, `expires_at`, `user_agent`, `ip_address`. This endpoint reads and writes rows in that existing table — delete old row, insert new row.

**Implementation note (not a schema change):** the new row's `user_agent` and `ip_address` are captured from the **current** refresh request (read from `User-Agent` header / `X-Forwarded-For`, same as login), not copied forward from the row being replaced. Copying the original login's values forward would leave the audit trail permanently pointing at whatever device first logged in, even after days of refreshes from a different device — defeating the stated purpose of those columns (`authentication.md`: "a future 'active sessions' UI can display this information").

**Implementation note (Clock, not a schema change):** the `expires_at < now()` comparison must use the injectable `Clock` bean (`testing-strategy.md` §7), not `Instant.now()`/`LocalDateTime.now()` directly. This is what makes the expiry-boundary edge case (§6) testable with a fixed clock instead of a real sleep.

---

## 5. Validation rules

No request body, so no field-level validation in the usual sense. The only input is the `refresh_token` cookie:

| Field | Constraint | Error |
|---|---|---|
| `refresh_token` cookie | Must be present | 401, `auth/refresh-token-invalid` |
| `refresh_token` cookie value | Max length 256 bytes | 401, `auth/refresh-token-invalid` — rejected **before** hashing/querying the DB |
| `refresh_token` cookie value | No other format constraint — opaque value, hashed and looked up as-is | A non-matching value (within the length bound) falls through to the same `401` "not found" path |

**Why the length cap:** this route is public and unauthenticated — not behind API Gateway's JWT authorizer — so nothing stops a non-browser client from sending an arbitrarily large `Cookie` header directly at the ALB. The real token is a base64/hex encoding of 32 random bytes (well under 100 bytes either way); 256 bytes leaves generous headroom for encoding variance while bounding the hashing/DB-lookup cost per request regardless of caller.

---

## 6. Edge cases

| Scenario | Status | `type` | Notes |
|---|---|---|---|
| No `refresh_token` cookie | 401 | `auth/refresh-token-invalid` | |
| Cookie longer than 256 bytes | 401 | `auth/refresh-token-invalid` | Rejected before hashing — see §5 |
| Cookie present, hash not found in `refresh_tokens` | 401 | `auth/refresh-token-invalid` | Same type as "missing cookie" — do not distinguish, to avoid signaling anything to a caller probing with garbage values |
| Cookie present, row found, but `expires_at < now()` | 401 | `auth/refresh-token-expired` | Per `authentication.md` step 4, the expired row is also deleted as part of handling this case. Uses the injected `Clock`, not a direct `now()` call — see §4 |
| Concurrent refresh with the same token (race: two requests present the same cookie near-simultaneously) | First: 200. Second: 401 | Second: `auth/refresh-token-invalid` | Second request's lookup misses, since the winning transaction already deleted the row. This is the mechanism `authentication.md` describes for detecting theft — the losing side should force a re-login, not silently retry. **Known blind spot:** this produces the exact same log line and response as a genuine stolen-token replay attempt — the server cannot distinguish a benign race loser from an attacker server-side. Documented here so a future incident review doesn't assume the logs can tell them apart. |
| DB transaction fails mid-rotation (delete+insert doesn't commit) | 500 | — | Neither side of the rotation partially applies — the original cookie is still valid, so the client can safely retry the same request |
| User row deleted after the refresh token was issued | 401 | `auth/refresh-token-invalid` | Falls into the same "not found" path automatically — `refresh_tokens.user_id` has `ON DELETE CASCADE`, so the row is already gone; no special-case code needed |
| Cookie's `user_agent`/`ip_address` differs from the ones stored at issuance | No effect — request proceeds normally | — | `authentication.md` is explicit that these columns are audit-only, not used for validation; noting this so it's not accidentally implemented as an extra check |
| Refresh succeeds while the caller's prior access token is still unexpired | 200 | — | Prior access token is not revoked or blocklisted by this call — see §2 |
| User has other active sessions (other devices) at time of refresh | 200 | — | Only the presented session's row is rotated; other `refresh_tokens` rows for the same `user_id` are untouched — see §2 |

---

## 7. Authorization

No RBAC dimension — this endpoint is not workspace-scoped, so `authorization.md`'s admin/member model doesn't apply. There is no `@PreAuthorize`/`hasWorkspaceRole` check, and no `X-User-Id`/`X-User-Workspaces` headers exist for this call (it's one of the three unauthenticated routes per `api-conventions.md`).

- **Who can call it:** anyone possessing a valid `refresh_token` cookie value whose hash matches an unexpired row in `refresh_tokens`. Identity comes entirely from that DB lookup, not from a JWT claim.
- **"Unauthenticated" case:** no cookie, or cookie that doesn't resolve to a live row → `401 auth/refresh-token-invalid`.
- **No "wrong role" case exists** for this endpoint — there's no role to be wrong about.

---

## 8. Observability

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful refresh | INFO | `event=token_refreshed userId={} jti={} ip={} correlationId={}` — `jti` is the newly minted access token's ID, required so a token minted purely via refresh (no login anywhere in its history) still has a traceable point of origin in the jti lineage (login/register → refresh → blocklist → logout) |
| Refresh rejected — expired | WARN | `event=token_refresh_failed reason=expired userId={} ip={} correlationId={}` — `userId` is included here because the row (and its owner) was already found before being deleted; no reason to discard it |
| Refresh rejected — missing/invalid | WARN | `event=token_refresh_failed reason={missing_cookie\|not_found} ip={} correlationId={}` — no `userId`, since it's genuinely unknown in these two cases |

**Audit events:** "Token refresh" already exists as a row in `authentication.md`'s audit events table. **That row needs a documentation fix as part of this PR:** it currently lists only `userId`, `ip`, `correlationId` — no `jti` — but the paragraph directly beneath the table says the "Member removed (self)" traceability gap is "already covered by the Token refresh audit event," which is only true if Token refresh logs `jti`. This is a pre-existing self-contradiction in `authentication.md`, surfaced during this plan's adversarial review. Fix: add a `jti` column to the "Token refresh" row in that table, matching what this plan's log line above actually does.

**Correlation ID:** read from `X-Correlation-ID` on the incoming request (or generated if absent, per `api-conventions.md`), attached to MDC for every log line in this request, echoed on the response — same as every other endpoint in this service.

---

## 9. Out of scope

- No logout endpoint.
- No database schema change.
- No new SNS topics.
- Rate limiting / brute-force throttling on this endpoint — not added. The refresh token's 256-bit entropy makes guessing infeasible regardless of request rate, so throttling would add complexity without closing a real gap at this project's scale. Noted here as a conscious decision, not a silent omission.
