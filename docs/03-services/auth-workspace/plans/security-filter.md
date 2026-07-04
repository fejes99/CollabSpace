# Plan: security-filter

**Branch:** `feat/auth/security-filter`
**Tier:** Full
**Service:** auth-workspace
**Status:** Draft

---

## 1. Slice statement

auth-workspace validates `X-Internal-Token` on every request, populates Spring Security's `SecurityContext` from `X-User-Id`/`X-User-Workspaces`, and rejects requests whose JWT `jti` is present in the Redis blocklist. No business endpoint is added or changed. After this lands, `@PreAuthorize` becomes meaningful for the first time in this service.

Prerequisites (already merged): `X-User-Id`, `X-User-Workspaces`, `X-JWT-Jti` are now actually forwarded by API Gateway on the JWT-authorized routes (`infra/auth-claim-headers`), stripped on public routes so a client can never forge them there (follow-up in the same PR), and `memberships` is issued as a JSON string claim, not a nested array (`fix/auth/membership-claim-string`).

---

## 2. User-visible behavior

No new endpoint. Observable via response codes on existing/future routes:

| Request condition | Response |
|---|---|
| `/v1/auth/{proxy+}` or `/v1/workspaces/{proxy+}`, missing/wrong `X-Internal-Token` | `401` |
| Same, correct token, no `X-User-Id` (shouldn't happen — API Gateway's authorizer already required a valid JWT to reach here) | `401` |
| Same, correct token, `X-User-Id` present, `X-User-Workspaces` malformed JSON | `401` |
| Same, `X-JWT-Jti` present and blocklisted in Redis | `401` |
| Same, `X-JWT-Jti` absent, or Redis unreachable | passes through (nothing to check — see §4) |
| `POST /v1/auth/register` / `login`, correct `X-Internal-Token` | passes through, anonymous `SecurityContext` (no identity headers on this route at all — see §3) |
| `/.well-known/**`, `/actuator/health/**` | internal-token check bypassed entirely (see §3) |

---

## 3. Route exemptions

Two categories, for two different reasons — worth keeping distinct rather than one blanket rule.

**`/.well-known/**`** — called by API Gateway's own infrastructure (JWKS fetch, OIDC discovery at authorizer-creation time), not routed through the VPC Link at all. Never carries `X-Internal-Token`. Exempt by path match, unconditionally.

**`/actuator/health/**`** — two different callers hit this path prefix, and they need different treatment:
- `GET /actuator/health` (root) *is* routed through API Gateway (the `auth_health` route, public integration) and does carry `X-Internal-Token` on that path — normal validation applies.
- `GET /actuator/health/readiness` has **no API Gateway route at all** (confirmed in Terraform — only ECS's own container-level health check calls it, per ADR-031: `curl http://localhost:8080/actuator/health/readiness` from inside the same container). That request arrives over loopback and can never carry the token.

So the exemption is the intersection of path *and* origin, not path alone: bypass the internal-token check only when the path is `/actuator/health/readiness` or `/actuator/health/liveness` **and** the caller's address matches loopback. This is the narrowest correct rule — it doesn't create a standing "anyone in the VPC can skip validation on this path" hole, since the bypass only ever fires for a request that could only physically originate inside that exact container's own network namespace. `/actuator/health` (root) stays under normal enforcement; real traffic to it always arrives via the gateway and always carries the token.

Implementation detail: use `org.springframework.security.web.util.matcher.IpAddressMatcher` for the loopback check rather than comparing `request.getRemoteAddr()` against string literals — it correctly handles both IPv4 (`127.0.0.1`) and IPv6 (`::1`) loopback forms.

The more common production answer to this class of problem is a separate `management.server.port`, making the trust boundary structural (the security group never exposes it) rather than something a filter has to reason about. Not adopted here: it's a real infra change (new port, security-group rule, task-definition wiring) for a problem this filter already solves with zero infra impact. Worth revisiting if this service's health-check surface grows.

---

## 4. Filter design

Three `OncePerRequestFilter`s in `adapter/in/rest/security/` (not `filter/` — that package is for framework-agnostic filters like `CorrelationIdFilter`; these are Spring-Security-specific and registered on the `SecurityFilterChain` via `addFilterBefore`, in this order:

**1. `InternalTokenFilter`** — compares `X-Internal-Token` against the SSM-loaded value (mirrors the existing `JWT_PRIVATE_KEY`/`JWT_PRIVATE_KEY_SSM_PATH` local/AWS split — add `INTERNAL_TOKEN`/`INTERNAL_TOKEN_SSM_PATH`). 401 if missing/wrong. Applies the §3 exemptions.

**2. `HeaderAuthenticationFilter`** — reads `X-User-Id`, parses `X-User-Workspaces` JSON into the existing `WorkspaceMembership` record (`domain/model/auth/`), and sets a `PreAuthenticatedAuthenticationToken(userId, null, authorities)` on the `SecurityContextHolder`. `PreAuthenticatedAuthenticationToken` is the idiomatic Spring Security type for "identity already established by something upstream I trust" (the same family used for SSO-header integrations) — simpler than pulling in the full `AbstractPreAuthenticatedProcessingFilter` + `AuthenticationUserDetailsService` machinery for what's fundamentally one header pair.

If `X-User-Id` is absent (register/login — no identity headers reach these routes at all per the public integration), leaves the context anonymous; no rejection. If `X-User-Workspaces` is present but fails to parse, that's a `401` — it means API Gateway's mapping produced something unexpected, which should never happen silently.

Authority representation: a small custom `GrantedAuthority` (`WorkspaceAuthority(workspaceId, role)`) rather than string-concatenating into `SimpleGrantedAuthority("WORKSPACE_x_ADMIN")` and re-parsing later — PR 8's `hasWorkspaceRole` expression needs structured access to both fields.

**3. `JwtBlocklistFilter`** — reads `X-JWT-Jti`, checks Redis `blocklist:<jti>` via a new `TokenBlocklistRepository.isBlocklisted(jti)` outbound port (Redis-backed adapter using the existing `StringRedisTemplate` from PR 6.5). 401 if present. Only the read side is built now — the write side (`blocklist(jti, ttl)`) is added to the same interface by PR 12 (logout) when it has an actual caller; building it now would be untested, unused code.

**Error responses:** these filters run before `DispatcherServlet`, so `GlobalExceptionHandler` (`@RestControllerAdvice`) never sees anything thrown here — it only catches exceptions inside the MVC dispatch. A small shared helper writes an RFC 9457 body directly to the `HttpServletResponse`, matching the existing error shape even though it bypasses the normal handler path.

---

## 5. Redis blocklist — fail open

Resolves the open question flagged in `redis-client.md`'s plan. **Fail open**: on a Redis connection error, log WARN and treat the request as not-blocklisted rather than rejecting all authenticated traffic. authentication.md's own "Token revocation" section already documents this exact tradeoff — a Redis outage causing a 15-minute post-logout exposure window is accepted; a Redis outage rejecting all authenticated traffic service-wide would be strictly worse for a non-critical dev system. Same treatment when `X-JWT-Jti` is simply absent — nothing to check.

---

## 6. Carry-overs landing in this PR

- `jti` added to the `event=user_logged_in` log line (deferred here from PR 6 on purpose, per the login plan, so the full token lifecycle — issue → blocklist write → blocklist hit — is traceable in one PR).
- Swagger `apiKey` security schemes for `X-Internal-Token`/`X-User-Id`/`X-User-Workspaces` (`OpenApiConfig`, `@SecurityScheme`) so local "Try it out" doesn't 401 — there's no API Gateway locally to inject these headers, so the local workflow becomes: send a fixed `INTERNAL_TOKEN` value manually (register/login need it too, they're not exempt), and for anything protected, decode the JWT from login's response yourself and pass `X-User-Id`/`X-User-Workspaces` by hand. Swagger's "Authorize" button lets you set all three once instead of per-request.

---

## 7. Edge cases

| Scenario | Expected result |
|---|---|
| Missing/wrong `X-Internal-Token` | 401 |
| Correct token, `/v1/auth/register` or `/login` (no identity headers present at all) | passes, anonymous `SecurityContext` |
| Correct token, malformed `X-User-Workspaces` | 401 |
| Blocklisted `jti` | 401 |
| Absent `jti` | passes |
| Redis unreachable | passes + WARN log |
| `/.well-known/**` | bypass, any origin |
| `/actuator/health` (root) | normal validation (gateway-routed, carries token) |
| `/actuator/health/readiness`, loopback origin | bypass |
| `/actuator/health/readiness`, non-loopback origin | 401 (shouldn't be reachable at all — no gateway route exists for it, but the filter must not silently trust an unexpected caller) |

---

## 8. Test plan

Unit tests per filter with fabricated headers and a fabricated `RemoteAddr` (no real SSM/API Gateway available locally). Testcontainers `redis:7` for the blocklist filter's integration test, mirroring PR 6.5's pattern — including the fail-open case (stop the container mid-test, confirm the request still passes with a WARN logged).

---

## 9. Out of scope

- `hasWorkspaceRole` custom `@PreAuthorize` expression — PR 8 builds it against its first real caller.
- Blocklist *write* path — PR 12.
- Terraform claim-to-header mapping — already merged (`infra/auth-claim-headers`, `fix/auth/membership-claim-string`).
