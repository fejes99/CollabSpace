# Plan: Logout

**Branch:** `feat/auth/logout`
**Service:** auth-workspace
**Tier:** Full — changes the authentication/token-revocation flow (Redis blocklist write side), per the tier table in `plan-feature`'s own guidance, even though it is a single endpoint with no schema change and no new dependency.

---

## 1. Slice statement

Implement `POST /v1/auth/logout`, which deletes the caller's refresh token row, blocklists the current access token's `jti` in Redis, and clears the refresh-token cookie.

**IN**
- Deletes the `refresh_tokens` row matching the hashed `refresh_token` cookie value.
- Writes `blocklist:<jti>` to Redis (from `X-JWT-Jti`) with a TTL covering the token's remaining lifetime.
- Clears the `refresh_token` cookie (`Max-Age=0`) and returns `200`.

**OUT**
- "Logout everywhere" — revoking all of a user's sessions/refresh tokens at once. This endpoint only ever touches the single session presented by the current request (one cookie, one `jti`), same single-session scope `token-refresh.md` uses for rotation.
- Any change to `JwtBlocklistFilter`'s read side (the blocklist check on every authenticated request) — that was built in PR 7. This PR is the write side only.
- A Resilience4j/circuit-breaker hybrid for the Redis fail-open behavior — flagged as future work in `notes/auth-workspace-prs.md` PR 11, not part of this slice.

---

## 2. User-visible behavior

- `POST /v1/auth/logout` with a valid access token and a matching `refresh_token` cookie returns `200` and clears the cookie (`Set-Cookie: refresh_token=; ...; Max-Age=0`).
- After logout, the same access token's `jti` is rejected with `401 auth/token-revoked` on any subsequent authenticated request, for the remainder of what would have been its 15-minute lifetime.
- After logout, calling `POST /v1/auth/refresh` with the now-deleted `refresh_token` cookie returns `401 auth/refresh-token-invalid`.
- Calling `POST /v1/auth/logout` twice in a row using the **same** access token returns `200` the first time and `401 auth/token-revoked` the second — the first call already blocklisted that `jti`, so `JwtBlocklistFilter` rejects the retry before it ever reaches this controller. Logout is idempotent in effect (the session is over either way), but not uniformly `200` on retry — see §6.
- Calling `POST /v1/auth/logout` with no `refresh_token` cookie at all (already expired or cleared client-side) still returns `200` and still blocklists the `jti` — the endpoint's job is "make sure this session is over," not "assert a cookie existed."

---

## 3. API contract

**Path:** `POST /v1/auth/logout`
**Auth:** Bearer JWT required — a standard authenticated route. `X-User-Id` and `X-JWT-Jti` arrive via API Gateway per `api-gateway-trust.md`; this is not one of the three unauthenticated routes (`register`/`login`/`refresh`).
**Request body:** None.
**Response body (happy path):** `200 OK`, no body — matching `authentication.md`'s and the PR spec's literal "Return 200" (unlike `remove-member`'s bare `204`, this stays `200` with an empty body, per both source documents).

Plus a clearing `Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/v1/auth; Max-Age=0`.

**Implementation note — `Path` must be `/v1/auth`, not `/auth`.** `services/auth-workspace/README.md` and `authentication.md` both document the refresh-token cookie as `Path=/auth`, but the actual shipped code (`AuthController.setRefreshTokenCookie`, used by both login and refresh) sets `Path=/v1/auth`. A browser only overwrites/deletes a cookie when the clearing `Set-Cookie`'s `Path` matches exactly — clearing with `Path=/auth` would silently fail to remove the real cookie, leaving it live in the browser even though the server-side row and blocklist state are correctly revoked. Logout must reuse (or exactly mirror) `setRefreshTokenCookie`'s existing `Path`/`HttpOnly`/`Secure`/`SameSite` attributes with `Max-Age=0`, not re-derive them from the docs. See §8 for the doc-fix.

**Status codes**

| Code | Scenario | `type` |
|---|---|---|
| 200 | Refresh token row deleted, or already absent (no-op); `jti` blocklisted, or blocklist write failed open (see §6) | — |
| 401 | No valid access token — handled by the existing filter chain (`InternalTokenFilter`/`HeaderAuthenticationFilter`/`JwtBlocklistFilter`/`MembershipStalenessFilter`) before the request reaches this controller | existing `auth/*` types, unchanged |
| 500 | `refresh_tokens` `DELETE` fails for a reason other than "no matching row" (genuine DB failure) | generic internal-error handling already in place via `GlobalExceptionHandler` — no new `type` needed |

**Implementation note — deriving `remaining_ttl` without an `exp` header.** `authentication.md` §Logout describes the blocklist write as `SET blocklist:<jti> 1 EX <remaining_ttl>` where `remaining_ttl = max(0, exp - now())`. But `api-gateway-trust.md`'s forwarded-header table has no `X-JWT-Exp` — only `X-JWT-Jti` and `X-JWT-Iat`. Rather than adding a new header (an infra/Terraform change, out of scope for a service-only PR), this endpoint derives it the same way `MembershipStalenessFilter` already does: `remaining_ttl = max(0, (iat + ACCESS_TOKEN_TTL_SECONDS) - now())`, using the existing `X-JWT-Iat` header and the fixed 15-minute (`900`s) access token lifetime from `authentication.md` §Token design. See §8 for the accompanying `authentication.md` doc-fix.

---

## 4. Data model changes

None. `refresh_tokens` already exists (`V3__create_refresh_tokens.sql`) — this endpoint only deletes a row from it. The blocklist is the existing `blocklist:<jti>` Redis key pattern, first *written* here — the read side already exists (`JwtBlocklistFilter`, PR 7).

**Implementation note (Clock, not a schema change):** both the `remaining_ttl` computation and the "is this token already past its natural expiry" check (§6) must use the injectable `Clock` bean (`testing-strategy.md` §7), not `Instant.now()` directly — same convention `token-refresh.md` §4 already established for its `expires_at < now()` check.

---

## 5. Validation rules

No request body. The only inputs are the `refresh_token` cookie (optional) and the `X-JWT-Jti`/`X-JWT-Iat` headers, which arrive pre-validated by the existing filter chain on every authenticated request and are not re-validated by this endpoint.

| Field | Constraint | Error |
|---|---|---|
| `refresh_token` cookie | Optional — absence is a no-op, not an error | — |
| `refresh_token` cookie value | Max length 256 bytes | Rejected before hashing/querying the DB; treated as the no-op path (same as "absent"), not an error — see note below |
| `refresh_token` cookie value | No other format constraint — hashed (SHA-256) and looked up as-is; a non-matching value (within the length bound) falls into the same no-op path as "absent" | — |

**Why the length cap, despite this endpoint requiring auth (unlike `/v1/auth/refresh`'s public cap):** the `refresh_token` cookie is an independent HTTP header from the `Authorization` bearer token — a caller who legitimately holds a valid access token can still attach an arbitrarily large `Cookie` header. The threat model is weaker than `/v1/auth/refresh`'s (a valid token is required first), but the same 256-byte cap from `token-refresh.md` §5 is applied here too, for the same reason: bound the hashing/DB-lookup cost per request regardless of caller. Rejected silently into the no-op path rather than a `400`, since an oversized value isn't a client error worth surfacing — it just can't possibly match a real token.

---

## 6. Edge cases

| Scenario | Status | `type` | Notes |
|---|---|---|---|
| No `Authorization` header / invalid or expired access token | 401 | existing `auth/*` types | Rejected by the existing filter chain before reaching this controller — no logout-specific handling |
| No `refresh_token` cookie present | 200 | — | No-op on the DB delete step; `jti` is still blocklisted and the (already-absent) cookie is still cleared |
| `refresh_token` cookie present, but hash matches no row (already logged out, duplicate request) | 200 | — | Same no-op path as "no cookie." Reached only when the presented access token's `jti` **isn't already blocklisted** — e.g. two near-simultaneous first-time logout calls racing, not a same-token retry (see the row below, and §2) |
| `refresh_token` cookie present, row found, but `expires_at < now()` | 200 | — | Deleted the same as any other matching row — expiry doesn't change delete behavior, only `/v1/auth/refresh`'s lookup treats expiry specially |
| `refresh_tokens` `DELETE` fails for a genuine DB error (not "no row") | 500 | — | Abort before attempting the Redis write or clearing the cookie — if the row might still exist, don't tell the client (via a cleared cookie) that the session is over. Client can safely retry. |
| Redis unreachable during the blocklist `SET` | 200 | — | **Fail open**, matching the accepted gap in `authentication.md` §Token revocation: refresh token is still deleted (hard revocation), cookie is still cleared, but the `jti` isn't blocklisted. Log at ERROR (see §8). Same accepted risk window as any other blocklist fail-open case — up to 15 minutes. |
| Access token's `iat + 900s` is already in the past at logout time | 200 | — | **Not reachable in production through API Gateway** — its JWT Authorizer validates `exp` and returns `401` upstream before the request reaches this service at all (`api-gateway-trust.md`). Reachable only in local dev (no API Gateway in front, `exp` never checked) or via clock skew between API Gateway and this service. `remaining_ttl` computes to `0` or negative in that case; skip the Redis `SET` entirely rather than clamping to `EX 0` (Redis rejects a zero/negative expire) |
| Duplicate/retry logout call using the **same** access token | 401 | `auth/token-revoked` | Rejected by `JwtBlocklistFilter` before reaching this controller — the first call already blocklisted the `jti`. This is the realistic retry case (lost response, double-click, naive client retry) and does **not** go through the controller's no-op path above. Functionally still "logged out" — client retry logic should treat this `401` as a success signal, not an error, for this specific endpoint |
| `refresh_token` cookie longer than 256 bytes | 200 | — | Rejected before hashing — treated as the no-op path, same as "no cookie" — see §5 |
| Caller's `iat` predates their own `membership-changed-at:<userId>` marker (stale claims) | 401 | `auth/claims-stale` | Rejected by `MembershipStalenessFilter` before reaching this controller, same as any other authenticated route — logout consumes no membership claim, but staleness enforcement applies uniformly with no per-route exemption. Accepted as-is; see §7 and §9. Caller must call `POST /v1/auth/refresh` first to obtain a token fresh enough to be allowed to log out |

**Note — lost response leaves a harmless stale cookie.** If the first call's `200` and its clearing `Set-Cookie` are both lost in transit (client never receives them), the browser keeps sending the old `refresh_token` cookie. This is functionally harmless: the server-side row is already deleted, so any future `/v1/auth/refresh` using that cookie correctly `401`s with `refresh-token-invalid`. There's no logout-triggered retry path to clear it sooner, since a same-token retry now `401`s at the filter layer before reaching the cookie-clearing code (row above) — noted here so it isn't mistaken for a bug later.

---

## 7. Authorization

No RBAC dimension — this is a self-directed session-lifecycle action, not a workspace resource, so `authorization.md`'s admin/member model doesn't apply. No `@PreAuthorize`/`hasWorkspaceRole` check.

- **Who can call it:** any authenticated user, acting only on their own session — the endpoint reads no path or body parameter naming another user or workspace.
- **Unauthenticated case:** no valid access token → `401`, via the existing filter chain, before this controller is reached.
- **No wrong-role case exists** — there's no role to be wrong about.
- **No cross-user surface:** the endpoint only ever acts on the caller's own `X-User-Id`-scoped refresh token and `X-JWT-Jti`-scoped blocklist entry, both derived from the caller's own validated token — there's no way to name another user's session.

**Known interaction, accepted as-is:** `MembershipStalenessFilter` runs on every authenticated request, including this one, and rejects a stale-claims caller with `401 auth/claims-stale` (§6) even though logout consumes no membership claim at all. This is a real oddity — a check meant to protect access to *other* resources ends up blocking the "end my session" action itself, forcing a user whose role just changed to call `/v1/auth/refresh` before they're allowed to log out. Exempting this route would mean adding a path-based carve-out to shared security-filter infrastructure, not a change scoped to this endpoint, so it's deliberately not done here — see §9. Decision: accept the existing uniform behavior for this PR.

---

## 8. Observability

**Log lines:**

| Scenario | Level | Event |
|---|---|---|
| Successful logout (row deleted or no-op, blocklist write succeeded) | INFO | `event=user_logged_out userId={} jti={} correlationId={}` |
| Blocklist write failed open (Redis unreachable) | ERROR | `event=logout_blocklist_write_failed userId={} jti={} correlationId={}` — mirrors the existing "Blocklist check failure" audit row in `authentication.md`, but for the write side instead of the read side |
| `refresh_tokens` `DELETE` failed for a genuine DB error | ERROR | `event=logout_failed reason=db_error userId={} correlationId={}` |

**Audit events:** the "Logout" row already exists in `authentication.md`'s audit events table (`userId`, `jti`, `correlationId`) and matches this plan's success log line exactly — no doc fix needed there, unlike `token-refresh.md`'s finding for the "Token refresh" row.

**`authentication.md` doc-fix needed (surfaced by §3's implementation note):** §Token revocation's line `remaining_ttl = max(0, exp - now())` implies an `exp` value is available to the service. It isn't — only `X-JWT-Iat` is forwarded (see `api-gateway-trust.md`'s header table). Fix in the same PR: reword to `remaining_ttl = max(0, (iat + access_token_lifetime) - now())`, consistent with how `MembershipStalenessFilter` already derives token age from `iat` alone.

**Second `authentication.md` / README doc-fix needed (verified against code):** both `authentication.md` (§Refresh tokens, §Auth flows) and `services/auth-workspace/README.md` document the refresh-token cookie as `Path=/auth`. The actual shipped code (`AuthController.setRefreshTokenCookie`, confirmed at `services/auth-workspace/src/main/java/com/collabspace/authworkspace/adapter/in/rest/auth/AuthController.java:143`) sets `Path=/v1/auth`. Both docs are stale relative to the merged `token-refresh.md`/PR #53 implementation, which already uses `/v1/auth` in its own example. Fix both docs in this PR — this isn't just cosmetic, since a future implementer copying `Path=/auth` from either doc (rather than reusing the existing cookie-building code) would ship a logout that fails to actually clear the browser's cookie (see §3).

**Correlation ID:** read from `X-Correlation-ID` on the incoming request, attached to MDC by the existing `CorrelationIdFilter`, echoed on the response — no new propagation logic, same as every other endpoint in this service.

---

## 9. Out of scope

- "Logout everywhere" — revoking all of a user's sessions/refresh tokens at once.
- Any change to `JwtBlocklistFilter`'s read side (built in PR 7) — this PR is write-side only.
- A Resilience4j/circuit-breaker hybrid for the Redis fail-open behavior (flagged as future work in PR 11's notes, not part of this slice).
- Exempting `/v1/auth/logout` from `MembershipStalenessFilter`'s staleness check (§6, §7). The filter applies uniformly to every authenticated route today; carving out an exception for this one route is a change to shared security-filter infrastructure, deliberately left as a future decision rather than made silently here.
