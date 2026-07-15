# Error Type Catalog

Single source of truth for the RFC 9457 `type` URIs used across CollabSpace's HTTP APIs. See [api-conventions.md](api-conventions.md) (§ Error format) for the Problem Details shape itself — this document only tracks the `type` values, so a new plan doesn't have to invent a naming convention from scratch or accidentally collide with one already in use.

Each `type` is a stable, documentable identifier for one specific failure condition — never reused for a different meaning, never changed once a client may depend on it (see `api-conventions.md`'s definition of a breaking change).

All URIs use the `https://errors.collabspace.io/` prefix, grouped by domain.

---

## `validation/`

| `type` | Status | Meaning |
|---|---|---|
| `validation/invalid-request` | 400 | Request body fails Bean Validation / input format checks. Carries an `errors` array (see `api-conventions.md`). |

---

## `authorization/`

Defined in [authorization.md](authorization.md).

| `type` | Status | Meaning |
|---|---|---|
| `authorization/not-a-member` | 403 | Caller has no membership in the requested workspace (or the workspace doesn't exist from the caller's perspective — deliberately not distinguished, to avoid leaking workspace existence). |
| `authorization/insufficient-role` | 403 | Caller is a member but their role doesn't permit the operation. |

---

## `auth/`

Defined in [`docs/03-services/auth-workspace/plans/security-filter.md`](../03-services/auth-workspace/plans/security-filter.md) (PR 7).

| `type` | Status | Meaning |
|---|---|---|
| `auth/invalid-internal-token` | 401 | `X-Internal-Token` missing or incorrect. |
| `auth/malformed-identity-headers` | 401 | `X-User-Id`/`X-User-Workspaces` inconsistent, malformed, or oversized — see security-filter.md §4 for the full validation table. |
| `auth/unexpected-identity` | 401 | Identity headers present on a route defined as anonymous (`/v1/auth/register`, `/v1/auth/login`) — signals the API Gateway header-stripping guarantee has regressed. |
| `auth/token-revoked` | 401 | `jti` present in the Redis blocklist. |
| `auth/insufficient-authentication` | 401 | `anyRequest().authenticated()` rejected a request that reached `ProblemDetailsSecurityHandler.commence()` with a plain Spring Security `AuthenticationException` rather than one of the `auth/*` types above — i.e. no security filter treated the request as anonymous, but nothing populated a real `Authentication` either. |
| `auth/access-denied` | 403 | Generic `AccessDeniedException` reaching `ProblemDetailsSecurityHandler.handle()` — an authenticated caller was denied by a method-security check (`@PreAuthorize`) that isn't one of the more specific `authorization/*` types. See `authorization/insufficient-role` for the workspace-RBAC-specific case once it exists. |

Reserved, not yet in use: `auth/claims-stale` — for the membership-staleness check described in `authentication.md` (§ Membership and role change invalidation) once its write side is scheduled (see `notes/auth-workspace-prs.md`). Deliberately kept distinct from `token-revoked` so the two remain distinguishable if both land.

---

## Adding a new `type`

- Pick the narrowest domain prefix that fits (`auth/`, `authorization/`, `validation/`, or a new one if none fit — add it as its own section here).
- One `type` per distinct cause a caller might want to handle differently — not one per HTTP status code.
- Add the row here in the same PR that introduces the error, not after.
