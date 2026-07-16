# Plan: security-filter

**Branch:** `feat/auth/security-filter`
**Tier:** Full
**Service:** auth-workspace
**Status:** Ready

---

## 1. Slice statement

auth-workspace validates `X-Internal-Token` on every request, populates Spring Security's `SecurityContext` from `X-User-Id`/`X-User-Workspaces`, and rejects requests whose JWT `jti` is present in the Redis blocklist. No business endpoint is added or changed. After this lands, `@PreAuthorize` becomes meaningful for the first time in this service.

Prerequisites (already merged): `X-User-Id`, `X-User-Workspaces`, `X-JWT-Jti` are now actually forwarded by API Gateway on the JWT-authorized routes (`infra/auth-claim-headers`), stripped on public routes so a client can never forge them there (follow-up in the same PR), and `memberships` is issued as a JSON string claim, not a nested array (`fix/auth/membership-claim-string`).

The raw JWT itself is never forwarded to this or any downstream service — API Gateway terminates it entirely and forwards only the extracted claim headers above. See [api-gateway-trust.md](../../../02-architecture/api-gateway-trust.md) and [api-conventions.md](../../../02-architecture/api-conventions.md) (§ Authentication header).

---

## 2. User-visible behavior

No new endpoint. Observable via response codes on existing/future routes:

| Request condition | Response |
|---|---|
| `/v1/auth/{proxy+}` or `/v1/workspaces/{proxy+}`, missing/wrong `X-Internal-Token` | `401` |
| Same, correct token, no `X-User-Id` (shouldn't happen — API Gateway's authorizer already required a valid JWT to reach here) | `401` |
| Same, correct token, `X-User-Id` present, `X-User-Workspaces` malformed JSON | `401` |
| Same, `X-User-Id` present, `X-User-Workspaces` absent (or vice versa) | `401` — this combination should never occur; API Gateway's mapping sets both together or neither |
| Same, `X-User-Workspaces` exceeds size limits (see §4) | `401` |
| Same, `X-JWT-Jti` present and blocklisted in Redis | `401` |
| Same, `X-JWT-Jti` absent, or Redis unreachable | passes through (nothing to check — see §5) |
| `POST /v1/auth/register` / `login`, correct `X-Internal-Token`, no identity headers present | passes through, anonymous `SecurityContext` |
| `POST /v1/auth/register` / `login`, correct `X-Internal-Token`, identity headers unexpectedly present | `401` — fail closed, see §3 |
| `/.well-known/**`, `/actuator/health/**` | internal-token check bypassed entirely (see §3) |

---

## 3. Route exemptions

Three categories now — the original two plus a fail-closed rule for known-anonymous routes.

**`/.well-known/**`** — called by API Gateway's own infrastructure (JWKS fetch, OIDC discovery at authorizer-creation time), not routed through the VPC Link at all. Never carries `X-Internal-Token`. Exempt by path match, unconditionally.

**`/actuator/health/**`** — two different callers hit this path prefix, and they need different treatment:
- `GET /actuator/health` (root) *is* routed through API Gateway (the `auth_health` route, public integration) and does carry `X-Internal-Token` on that path — normal validation applies.
- `GET /actuator/health/readiness` has **no API Gateway route at all** (confirmed in Terraform — only ECS's own container-level health check calls it, per ADR-031: `curl http://localhost:8080/actuator/health/readiness` from inside the same container). That request arrives over loopback and can never carry the token.

So the exemption is the intersection of path *and* origin, not path alone: bypass the internal-token check only when the path is `/actuator/health/readiness` or `/actuator/health/liveness` **and** the caller's address matches loopback. This is the narrowest correct rule — it doesn't create a standing "anyone in the VPC can skip validation on this path" hole, since the bypass only ever fires for a request that could only physically originate inside that exact container's own network namespace. `/actuator/health` (root) stays under normal enforcement; real traffic to it always arrives via the gateway and always carries the token.

**Update from implementation:** this decision, including the alternatives considered and rejected, is written up formally in [ADR-033](../../../06-decisions/adr-033-loopback-health-probe-exemption.md).

Implementation detail: use `org.springframework.security.web.util.matcher.IpAddressMatcher` for the loopback check rather than comparing `request.getRemoteAddr()` against string literals — it correctly handles both IPv4 (`127.0.0.1`) and IPv6 (`::1`) loopback forms.

**Loopback check integrity — `forward-headers-strategy` must stay `NONE`.** `X-Forwarded-For` cannot be used to determine "is this request really from loopback," even via Tomcat's `RemoteIpValve`/trusted-proxy mechanism: that mechanism only defends against a *downstream* proxy injecting a fake hop, not against the *originating client* declaring `X-Forwarded-For: 127.0.0.1` itself — which is exactly the attacker's position here. Any security decision built on a forwarded-IP header is spoofable by the party the check exists to stop.

Resolution: `server.forward-headers-strategy` stays at its Spring Boot default (`NONE`) globally. `IpAddressMatcher` reads the true, unrewritten `getRemoteAddr()` — safe, because it reflects the actual TCP socket peer, which an attacker can't fake regardless of what headers they send. This does mean the login/registration audit `ip` field (authentication.md's audit events table) cannot come from `getRemoteAddr()` either, since behind the ALB that would just be the load balancer's address for real traffic. That field is populated by manually reading `X-Forwarded-For` at the log-line call site only — explicitly documented as untrusted, best-effort, for observability purposes, and never used for any security decision. This keeps the two concerns (audit IP vs. loopback security check) on separate, differently-trusted code paths rather than one shared, dual-purpose signal.

The more common production answer to the loopback-bypass problem generally is a separate `management.server.port`, making the trust boundary structural (the security group never exposes it) rather than something a filter has to reason about. Not adopted here: it's a real infra change (new port, security-group rule, task-definition wiring) for a problem this filter already solves correctly with the `forward-headers-strategy=NONE` + raw-socket-address approach above. Worth revisiting if this service's health-check surface grows.

**Known-anonymous routes — fail closed on unexpected identity headers.** `POST /v1/auth/register` and `POST /v1/auth/login` are the only routes where API Gateway's mapping template is expected to omit `X-User-Id`/`X-User-Workspaces` entirely (see §1 prerequisites — public routes strip these). If either header is present on these two specific paths, that's not treated as "extra, ignorable data" — it means the Terraform-side strip has regressed. `HeaderAuthenticationFilter` rejects with `401` rather than silently proceeding with an anonymous `SecurityContext`, so a real infra regression surfaces immediately instead of being masked.

**Update from implementation — dev-tooling exemption, and a shared `SecurityExemptPaths` class.** Once `OpenApiConfig` gained `@SecurityScheme`s for the Swagger "Authorize" button (see §6), `/swagger-ui.html` and `/v3/api-docs` started failing with `401` — a browser loading the Swagger page can't attach `X-Internal-Token`/`X-User-Id` until *after* the page has loaded, so both filters rejected the page itself before it could ever be used to authorize. Fixed by adding a fourth exemption category: `/swagger-ui*` and `/v3/api-docs*`, unconditional by path, same as `.well-known/**`. This is local-tooling-only — never routed through API Gateway in AWS (the gateway has no route for these paths at all), so the exemption creates no production-facing hole.

The `.well-known/**` path check was already duplicated verbatim across `InternalTokenFilter` and `HeaderAuthenticationFilter` (flagged by code review). Adding a second exempt-path category on top of that duplication would have made it worse, so both checks were extracted into a new shared `SecurityExemptPaths` (`isWellKnownPath`, `isDevToolingPath`), and both filters now call it instead of keeping their own copies.

---

## 4. Filter design

Three `OncePerRequestFilter`s, registered on the `SecurityFilterChain` via `addFilterBefore`, in this order:

**Update from implementation:** originally scoped for `adapter/in/rest/security/` directly (reserving `filter/` for framework-agnostic filters like `CorrelationIdFilter`), the package was later split into `adapter/in/rest/security/filter/` (these three) and `adapter/in/rest/security/exception/` (the four `SecurityAuthenticationException` subtypes) once the flat package reached 10 files — see the `Reorganize security package` commit. `SecurityConfig`, `WorkspaceAuthority`, and `ProblemDetailsSecurityHandler` stay at the top level of `security/`.

**1. `InternalTokenFilter`** — compares `X-Internal-Token` against the SSM-loaded value (mirrors the existing `JWT_PRIVATE_KEY`/`JWT_PRIVATE_KEY_SSM_PATH` local/AWS split — add `INTERNAL_TOKEN`/`INTERNAL_TOKEN_SSM_PATH`). 401 if missing/wrong. Applies the §3 exemptions. Logs on rejection: `log.warn("event=internal_token_invalid ip={} correlationId={} path={}", ip, correlationId, request.getRequestURI())` — no `userId` is available yet at this point in the chain.

**2. `HeaderAuthenticationFilter`** — reads `X-User-Id`, parses `X-User-Workspaces` JSON into the existing `WorkspaceMembership` record (`domain/model/auth/`), and sets a `PreAuthenticatedAuthenticationToken(userId, null, authorities)` on the `SecurityContextHolder`. `PreAuthenticatedAuthenticationToken` is the idiomatic Spring Security type for "identity already established by something upstream I trust" (the same family used for SSO-header integrations) — simpler than pulling in the full `AbstractPreAuthenticatedProcessingFilter` + `AuthenticationUserDetailsService` machinery for what's fundamentally one header pair.

Validation rules, all fail-closed (401) except the two explicitly anonymous cases:

| Header | Absent | Present, empty | Present, malformed/oversized |
|---|---|---|---|
| `X-User-Id` | anonymous, only on `/v1/auth/register`\|`/login` (§3); `401` everywhere else, and `401` if present at all on those two routes | `401` | — (opaque ID, no further shape to check) |
| `X-User-Workspaces` | `401` if `X-User-Id` is present (inconsistent pair); anonymous only if `X-User-Id` is also absent | `401` | `401` (bad JSON, or exceeds size limits below) |

**Size limits on `X-User-Workspaces`**, enforced before attempting to parse: raw header value capped at **4KB**, decoded array capped at **100 entries**. Both reject with `401` without attempting a parse if exceeded. 4KB comfortably covers realistic per-user membership counts for a v1 collaboration product with headroom to spare, well under gateway/ALB header-size ceilings.

**Update from implementation:** the entry limit was reduced from an original 200 to 100. At the minimal valid entry size (`{"workspaceId":"...","role":"..."}`, ~38 bytes), 200 entries already total ~7.6KB — always exceeding the 4KB byte-size limit first, making the entry-count check unreachable dead code. 100 entries (~3.9KB at minimum size) stays under 4KB, so this check is actually the one that fires for a too-long list of small memberships. Found via mutation testing (disabling the 200-entry check produced zero test failures) before this shipped, not after.

**These numbers are reasoned, not measured or enforced anywhere else.** `JwtService.issueAccessToken` (PR 4, already merged) places no cap on the `memberships` list it serializes into the token — nothing upstream currently prevents a token from being issued with a membership count this filter would reject. There is no test or shared constant tying the two together; if `JwtService` is ever changed to allow more memberships, this filter would start rejecting otherwise-legitimate tokens with no signal pointing at the mismatch. Flagging rather than fixing here — capping `JwtService` symmetrically is a separate, small follow-up (`services/auth-workspace/.../JwtService.java`), not part of this PR's filter work.

Authority representation: a small custom `GrantedAuthority` (`WorkspaceAuthority(workspaceId, role)`) rather than string-concatenating into `SimpleGrantedAuthority("WORKSPACE_x_ADMIN")` and re-parsing later — PR 8's `hasWorkspaceRole` expression needs structured access to both fields.

**3. `JwtBlocklistFilter`** — reads `X-JWT-Jti`, checks Redis `blocklist:<jti>` via a new `TokenBlocklistRepository.isBlocklisted(jti)` outbound port (Redis-backed adapter using the existing `StringRedisTemplate` from PR 6.5). 401 if present. Logs on rejection, matching the mandatory "Blocklist check failure" audit event in authentication.md's audit table: `log.warn("event=blocklist_check_failed jti={} userId={} ip={} correlationId={}", jti, userId, ip, correlationId)`. Only the read side is built now — the write side (`blocklist(jti, ttl)`) is added to the same interface by PR 12 (logout) when it has an actual caller; building it now would be untested, unused code.

**Error responses:** these filters run before `DispatcherServlet`'s MVC dispatch, so `GlobalExceptionHandler` (`@RestControllerAdvice`) never sees anything thrown here. Rather than each filter writing an RFC 9457 body to `HttpServletResponse` directly, all three delegate to one shared component, `ProblemDetailsSecurityHandler`, implementing both `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403), registered once in `SecurityConfig` via `.exceptionHandling(handling -> handling.authenticationEntryPoint(...).accessDeniedHandler(...))`.

Each filter constructs a specific `AuthenticationException` subtype describing what failed (`InvalidInternalTokenException`, `MalformedIdentityHeadersException`, `TokenRevokedException`) and calls `problemDetailsSecurityHandler.commence(request, response, exception)` directly, rather than throwing and relying on `ExceptionTranslationFilter` to catch it — these three filters run *before* `ExceptionTranslationFilter` in the chain (each filter only wraps what runs after it via `chain.doFilter()`), so an exception thrown here would propagate straight to the servlet container, not to Spring Security's exception handling. Calling the entry point directly is the same pattern Spring Security's own `BasicAuthenticationFilter` uses internally for the same reason.

`AccessDeniedHandler` is wired on the same bean now even though nothing in this PR throws `AccessDeniedException` yet: PR 8's `@PreAuthorize` checks run inside `DispatcherServlet`, which *is* wrapped by `ExceptionTranslationFilter` (positioned right before it in the chain), so PR 8's 403s will be routed to the same handler automatically, with no extra wiring, and render with the identical RFC 9457 shape these 401s use.

**Error `type` URIs** — four new `auth/` types, one per distinct cause. Full catalog (including these plus every other service's error types): [error-catalog.md](../../../02-architecture/error-catalog.md).

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
| Missing/wrong `X-Internal-Token` | 401, `invalid-internal-token`, logged |
| Correct token, `/v1/auth/register` or `/login`, no identity headers | passes, anonymous `SecurityContext` |
| Correct token, `/v1/auth/register` or `/login`, `X-User-Id` unexpectedly present | 401, `unexpected-identity` (§3) |
| Correct token, protected route, `X-User-Id` absent | 401, `malformed-identity-headers` |
| Correct token, protected route, `X-User-Id` present, empty string | 401, `malformed-identity-headers` |
| Correct token, `X-User-Id` present, `X-User-Workspaces` absent (or vice versa) | 401, `malformed-identity-headers` |
| Correct token, malformed `X-User-Workspaces` JSON | 401, `malformed-identity-headers` |
| `X-User-Workspaces` exceeds 4KB or 100 entries | 401, `malformed-identity-headers`, no parse attempted |
| Blocklisted `jti` | 401, `token-revoked`, logged (`event=blocklist_check_failed`) |
| Absent `jti` | passes |
| Redis unreachable | passes + WARN log |
| `/.well-known/**` | bypass, any origin |
| `/actuator/health` (root) | normal validation (gateway-routed, carries token) |
| `/actuator/health/readiness`, loopback origin | bypass (raw socket address, not `X-Forwarded-For` — see §3) |
| `/actuator/health/readiness`, non-loopback origin | 401 (shouldn't be reachable at all — no gateway route exists for it, but the filter must not silently trust an unexpected caller) |

---

## 8. Test plan

Unit tests per filter with fabricated headers and a fabricated `RemoteAddr` (no real SSM/API Gateway available locally). Testcontainers `redis:7` for `TokenBlocklistRedisAdapter`'s real-Redis behavior (key present/absent), plus the rejection-logging case in `JwtBlocklistFilterTest` (blocklisted `jti` produces the `event=blocklist_check_failed` line with all four required fields).

**Update from implementation:** the fail-open case does not stop a live Testcontainers Redis mid-test as originally planned — it uses a `LettuceConnectionFactory` pointed at a closed local port (`localhost:1`), mirroring the existing `RedisHealthCheckDownIntegrationTest` trick. Faster and more deterministic than container lifecycle manipulation, and avoids the flakiness risk of stop/start timing. Every filter branch (this included) was verified by mutation testing — temporarily breaking the implementation and confirming the correct test fails — not just written and trusted.

Additional cases beyond the original set: the size-limit rejection for `X-User-Workspaces` (both the byte-length and entry-count boundaries), the present-without-pair inconsistency for `X-User-Id`/`X-User-Workspaces`, and the fail-closed rejection when identity headers appear on `/v1/auth/register`/`login`. Confirm `server.forward-headers-strategy` is `NONE` in test config so the loopback test's fabricated `RemoteAddr` isn't silently overridden.

---

## 9. Out of scope

- `hasWorkspaceRole` custom `@PreAuthorize` expression — PR 8 builds it against its first real caller.
- Blocklist *write* path — PR 12. TTL will use a fixed 15-minute ceiling (the documented max access-token lifetime), not an exact `exp - now()` computation — avoids needing a new `X-JWT-Exp` forwarded header. Always safe since 15 minutes is a hard ceiling on remaining lifetime; the cost is a few minutes of harmless extra Redis retention in the common case.
- ~~Membership-staleness check (`membership-changed-at:<userId>` vs. token `iat`, per ADR-032) — no PR in the sequence currently owns the *write* side of this marker (no remove-member/change-role endpoint is planned yet), so there's no caller to exercise the read side against. Deferred until that endpoint is planned, following the same read-before-write-exists precedent as the blocklist filter above.~~ **Update:** invite-member (PR 9) turned out to be the first other-directed membership change, not the still-unscheduled remove-member/change-role endpoint this note assumed — PR 9 owns both the write and read side. See `docs/03-services/auth-workspace/plans/invite-member.md` and `MembershipStalenessFilter`.
- Terraform claim-to-header mapping — already merged (`infra/auth-claim-headers`, `fix/auth/membership-claim-string`).
