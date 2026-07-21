# auth-workspace

Authentication and workspace management service. Handles user registration, login, JWT issuance, and workspace RBAC. Built with Java 25 + Spring Boot 4.

**Current state:** Registration and login endpoints live. Refresh tokens stored in Postgres. JWT issued on register (access token) and login (access token + HttpOnly refresh cookie). Token refresh (`POST /v1/auth/refresh`) is implemented and tested; atomically rotates the refresh token (delete old row, insert new row, in the same transaction as issuing a new access token whose `memberships` claim is re-derived from the database, not copied from the old token — required for ADR-032's staleness invalidation to actually work) and returns `401` for a missing/unknown/oversized cookie (`auth/refresh-token-invalid`) or an expired one (`auth/refresh-token-expired`, distinguished deliberately — see the plan doc §3). Redis client wired and reporting health (ADR-030). `JwtBlocklistFilter` checks the `jti` blocklist in Redis on every authenticated request (fail-open on Redis errors); the write side (setting a `jti` on logout) lands in a later PR. Workspace creation is live — any authenticated user can create a workspace and becomes its first admin; the response reissues a fresh access token whose `memberships` claim reflects it immediately, per ADR-032. Inviting a member by email (`POST /v1/workspaces/{workspaceId}/members`, admin-only) is implemented and tested; publishes a `member.invited` event to SNS (ADR-037) and writes a `membership-changed-at` Redis marker so the invited user's and any other member's stale tokens are rejected until they refresh (ADR-032) — see `MembershipStalenessFilter` below. Changing a member's role (`PATCH /v1/workspaces/{workspaceId}/members/{userId}`, admin-only) is implemented and tested; rejects a demotion that would leave the workspace with zero admins, enforced atomically against concurrent demotions via a row-locking read (ADR-038). Self-demotion reissues a fresh access token in the response; an other-directed change publishes `member.role_changed` to SNS (ADR-037) and writes the same `membership-changed-at` marker as invite-member. Removing a member (`DELETE /v1/workspaces/{workspaceId}/members/{userId}`, admin-only) is implemented and tested; reuses change-member-role's last-admin invariant (ADR-038) and adds a creator-self-removal rule — the workspace creator can never remove their own membership, checked unconditionally before the invariant lock is ever acquired. Both self- and other-directed removal publish `member.removed` to SNS (ADR-037) and write the same `membership-changed-at` marker as a defense-in-depth backstop, but neither reissues an access token in the response — unlike self-demotion's `200`, self-removal returns a bare `204 No Content`, which structurally has no body to carry a token; the client is expected to call `POST /v1/auth/refresh` itself immediately afterward. Listing workspaces (`GET /v1/workspaces`) is implemented and tested; cursor-paginated (the first implementation of `api-conventions.md`'s pagination convention in this codebase — the reusable Base64/JSON codec mechanics live in `CursorCodec`, with `WorkspaceCursor` as this endpoint's thin typed wrapper) and deliberately **not** scoped to the caller's own memberships — any authenticated user sees every workspace in the system with its live member count. This is a considered exception to `authorization.md`'s membership-masking principle, not an oversight; see the plan doc's §7 for the full reasoning. A caller-scoped "my workspaces" variant is deferred to v1.5.

Every request is additionally gated by three Spring Security filters — internal-token validation, identity-header population, and the JWT blocklist check. See "Trust model" below and [docs/03-services/auth-workspace/plans/security-filter.md](../../docs/03-services/auth-workspace/plans/security-filter.md).

## What it does

- `GET /actuator/health` — returns `{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},...}}`. Routed through API Gateway (public integration) — requires a valid `X-Internal-Token` but no JWT. Returns `503` with `status=DOWN` if the database is unreachable. `redis` reflects live Redis reachability but does not affect what gates traffic — see `/actuator/health/readiness` below.
- `GET /actuator/health/readiness` — scoped to `db` only. This is the traffic-readiness signal: an ECS container-level health check polls it, and ECS's HEALTHY/UNHEALTHY status feeds Cloud Map, which is what API Gateway routes against — see ADR-031. A DB outage stops traffic routing here without killing/restarting the container (restarting never fixes an external Neon outage). Has no API Gateway route at all — only reachable from loopback, and `InternalTokenFilter` exempts it from the token check only when both the path and the caller's raw socket address are loopback.
- `GET /actuator/health/liveness` — left at Spring Boot's default (no external dependency checks). Answers "should this process be killed and restarted," which only makes sense for internally-fixable brokenness — not used by anything yet. Same loopback-only token exemption as `/readiness`.
- `GET /.well-known/jwks.json` and `GET /.well-known/openid-configuration` — RS256 public key set and OIDC discovery document. **Must remain public** — called by API Gateway's own infrastructure (JWKS fetch, OIDC discovery), never routed through the VPC Link, never carries `X-Internal-Token`. Exempt from the token check unconditionally by path. See ADR-026.
- `POST /v1/auth/register` — public route. No JWT required. Returns `201` with access token and user summary on success; `400` for validation errors; `409` if the email is already registered.
- `POST /v1/auth/login` — public route. No JWT required. Returns `200` with access token and user on success; sets an HttpOnly `refresh_token` cookie (`Path=/v1/auth`, `Max-Age=604800`, `Secure`, `SameSite=Strict`). Returns `400` for validation errors; `401` for invalid credentials.
- `POST /v1/auth/refresh` — public route. No JWT required — identity comes entirely from the `refresh_token` cookie, looked up by its SHA-256 hash. Returns `200` with a fresh access token and a rotated HttpOnly `refresh_token` cookie (same flags as login); the old cookie value stops working immediately. `401` `auth/refresh-token-invalid` if the cookie is missing, oversized (>256 bytes), or matches no row; `401` `auth/refresh-token-expired` if the row exists but has passed `expires_at` (the expired row is deleted as part of this rejection). See [docs/03-services/auth-workspace/plans/token-refresh.md](../../docs/03-services/auth-workspace/plans/token-refresh.md).
- `POST /v1/workspaces` — protected route. Any authenticated user may call it — creation is not role-gated (`authorization.md`). Returns `201` with the created workspace, the caller's role (`admin`), and a fresh access token embedding the new membership; `400` for validation errors; `401` if unauthenticated.
- `GET /v1/workspaces` — protected route, any authenticated user — **not** role- or membership-scoped (see `authorization.md`'s masking principle and the plan doc's §7 for why this is a deliberate exception, not a gap). Returns `200` with a cursor-paginated page of every workspace in the system: `{ data: [{ id, name, memberCount }], pagination: { hasNextPage, nextCursor, limit, count } }` — no `role` field, since role is a membership fact and this endpoint isn't scoped to any one caller's memberships. Query params: `limit` (default `20`, max `100`) and `after` (opaque Base64 cursor from a previous page's `nextCursor`). `400` `validation/invalid-request` for an out-of-range or non-numeric `limit`; `400` `validation/invalid-cursor` for a malformed `after`; `401` if unauthenticated.
- `POST /v1/workspaces/{workspaceId}/members` — protected route, admin-only (`@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`). Invites a registered user into the workspace by email. Returns `201` with the new membership; `400` for validation errors; `401` if unauthenticated; `403` if the caller isn't an admin member of the workspace (`authorization/not-a-member` or `authorization/insufficient-role`); `404` if no registered user matches the email; `409` if already a member. Publishes `member.invited` to the `workspace-events` SNS topic (ADR-037) and writes the invited user's `membership-changed-at` Redis marker (ADR-032) — both fail open (logged, response still succeeds) since neither has a consumer relying on synchronous delivery yet.
- `PATCH /v1/workspaces/{workspaceId}/members/{userId}` — protected route, admin-only (`@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`). Changes a member's role to `admin` or `member`. Returns `200` with the resulting membership (idempotent no-op if the requested role equals the current one — no DB write, no event, no marker write); `400` for validation errors; `401` if unauthenticated; `403` if the caller isn't an admin member of the workspace (`authorization/not-a-member` or `authorization/insufficient-role`); `404` if the target user has no membership in the workspace (`workspace/target-not-a-member`); `422` if the change would leave the workspace with zero admins (`workspace/last-admin-invariant`), enforced atomically against concurrent demotions via a row-locking read (ADR-038). Self-directed changes reissue a fresh access token in the response; other-directed changes write the target's `membership-changed-at` Redis marker (ADR-032) and publish `member.role_changed` to the `workspace-events` SNS topic (ADR-037) — both fail open.
- `DELETE /v1/workspaces/{workspaceId}/members/{userId}` — protected route, admin-only (`@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`). Removes a member's membership row, including the caller's own. Returns `204` with no body; `400` for validation errors; `401` if unauthenticated; `403` if the caller isn't an admin member of the workspace (`authorization/not-a-member` or `authorization/insufficient-role`); `404` if the target user has no membership in the workspace (`workspace/target-not-a-member`) — including a repeated `DELETE` for a target already removed, treated as idempotent rather than an error; `422` if the change would leave the workspace with zero admins (`workspace/last-admin-invariant`, reusing PATCH's row-locking read, ADR-038) or if the caller is the workspace's creator removing themselves (`workspace/creator-self-removal`, checked unconditionally before the last-admin lock is ever acquired — the workspace creator can never remove their own membership, but any other admin can remove the creator). Writes the target's `membership-changed-at` Redis marker (ADR-032) and publishes `member.removed` to the `workspace-events` SNS topic (ADR-037) for both self- and other-directed removal — both fail open. No access token is reissued for self-removal; a `204` has no body to carry one, so the client is expected to call `POST /v1/auth/refresh` itself.
- `GET /v3/api-docs` — OpenAPI 3.x JSON spec (internal tooling only, not routed through API Gateway).
- `GET /swagger-ui.html` — interactive Swagger UI for local development.

All responses include `X-Correlation-ID`. All errors return RFC 9457 Problem Details (`Content-Type: application/problem+json`).

### Trust model

Requests arriving from API Gateway carry four injected headers:

| Header | Source | Purpose |
|---|---|---|
| `X-Internal-Token` | API Gateway stage variable | Validated by `InternalTokenFilter` on every request — reject 401 if missing or wrong. Exempt for `.well-known/**` (unconditional), `/actuator/health/readiness`\|`liveness` (loopback origin only), and `/swagger-ui/**`\|`/v3/api-docs/**` (local dev tooling, never routed through API Gateway) |
| `X-User-Id` | JWT `userId` claim | Populated into `SecurityContextHolder` by `HeaderAuthenticationFilter` as a `PreAuthenticatedAuthenticationToken` principal |
| `X-User-Workspaces` | JWT `memberships` claim | Parsed by `HeaderAuthenticationFilter` into `WorkspaceAuthority` grants (4KB / 100-entry limits enforced before parsing) |
| `X-JWT-Jti` | JWT `jti` claim | Checked against the Redis blocklist by `JwtBlocklistFilter` — rejects 401 if revoked; fails open (passes through, logs WARN) if Redis is unreachable |
| `X-JWT-Iat` | JWT `iat` claim | Compared by `MembershipStalenessFilter` against the per-user `membership-changed-at` Redis marker — rejects 401 (`auth/claims-stale`) if the token was issued before an other-directed membership change; fails open if Redis is unreachable. See ADR-032 |
| `X-Correlation-ID` | API Gateway `$context.requestId` | Overwritten by `CorrelationIdFilter` into MDC — runs before the security filter chain so `correlationId` is available in their rejection logs |

`X-User-Id`/`X-User-Workspaces` are expected to be absent on `/v1/auth/register` and `/v1/auth/login` (and the exempt routes above) — present there, they fail closed instead of being treated as harmless extra data, since it would mean the API Gateway header-stripping guarantee has regressed. The service must **not** re-validate the JWT signature. See [api-gateway-trust.md](../../docs/02-architecture/api-gateway-trust.md) and [the security-filter plan](../../docs/03-services/auth-workspace/plans/security-filter.md).

`SecurityConfig`'s `authorizeHttpRequests` now requires authentication (`anyRequest().authenticated()`) for every route not explicitly listed as `permitAll()` — that list must stay in sync with `HeaderAuthenticationFilter.ANONYMOUS_PATHS`, or a route that filter treats as anonymous will be rejected by Spring Security's own authorization stage before it even reaches the filter's logic. `@EnableMethodSecurity` gates the invite-member, change-role, and remove-member endpoints via `@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")`, a custom SpEL expression resolved by `WorkspaceSecurityExpressionRoot`/`WorkspaceMethodSecurityExpressionHandler` — workspace creation itself needs no method-level annotation, since it isn't role-gated.

The JWT issuer (`iss`) and audience (`aud`) the service sets in issued tokens are read from SSM at startup:
- `/collabspace/dev/jwt/issuer` → `https://auth.dev.collabspace.io`
- `/collabspace/dev/jwt/audience` → `collabspace-api`

## Project structure

Hexagonal Architecture (Ports and Adapters) — see [ADR-025](../../docs/06-decisions/adr-025-hexagonal-architecture.md) for the rationale.

**Dependency rule:** dependencies point inward only — `adapter` → `application` → `domain`. Nothing in `domain/` or `application/` imports from `adapter/`. Browse the actual package tree in your IDE rather than here — a hand-maintained file listing goes stale the moment a file moves and has no way to self-correct; this rule doesn't.

## Running locally

```bash
cd services/auth-workspace
./mvnw spring-boot:run
```

The service starts on port 8080. To start auth-workspace in Docker (including Postgres), build the image, wait for health, and open Swagger all in one step:

```bash
make auth-swagger
```

| URL | What you see |
|---|---|
| `http://localhost:8080/actuator/health` | Health status |
| `http://localhost:8080/swagger-ui.html` | Interactive API explorer |
| `http://localhost:8080/v3/api-docs` | Raw OpenAPI JSON |

## Running tests

```bash
./mvnw test
```

## Code formatting

This service uses [Spring Java Format](https://github.com/spring-io/spring-javaformat). The Maven plugin enforces it in CI — `./mvnw validate` fails if any file is incorrectly formatted.

```bash
# Check formatting (runs automatically in CI via the validate phase)
./mvnw validate

# Apply formatting to all files
./mvnw spring-javaformat:apply
```

The IntelliJ plugin applies formatting on save. Install from the [GitHub releases page](https://github.com/spring-io/spring-javaformat/releases) via *Settings → Plugins → Install Plugin from Disk*.

## Building the Docker image

```bash
docker build --platform linux/amd64 -t auth-workspace:local .
docker run -p 8080:8080 auth-workspace:local
```

## Environment variables

**Local development** (set `JWT_PRIVATE_KEY` to activate this mode — no AWS credentials needed):

| Variable | Required | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://localhost:15432/auth_db` when running via Docker Compose |
| `SPRING_DATASOURCE_USERNAME` | Yes | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Postgres password |
| `JWT_PRIVATE_KEY` | Yes | Base64-encoded PKCS8 DER private key (no headers, no newlines). Generate: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \| openssl pkcs8 -topk8 -nocrypt -outform DER \| base64 \| tr -d '\n'` |
| `JWT_ISSUER` | Yes | JWT `iss` claim, e.g. `http://localhost:8080` |
| `JWT_AUDIENCE` | Yes | JWT `aud` claim, e.g. `collabspace-api` |
| `INTERNAL_TOKEN` | Yes | Fixed shared-secret value `InternalTokenFilter` compares `X-Internal-Token` against. No API Gateway locally, so send this value by hand on every request (register/login included — they're not exempt) |
| `SPRING_DATA_REDIS_URL` | No | `redis://localhost:16379` when running via Docker Compose. If unset, falls back to Spring's own default (`localhost:6379`) and logs a startup WARN. `JwtBlocklistFilter` fails open if unreachable, so the app still starts and serves traffic either way |
| `SNS_WORKSPACE_EVENTS_TOPIC_ARN` | Yes | Topic ARN `SnsWorkspaceEventPublisher` publishes `member.invited` to (ADR-037). No default — the app fails to start without it. LocalStack ARNs are deterministic (`arn:aws:sns:{region}:000000000000:{topic-name}`); requires the `localstack` docker-compose service (`make up`) and its topic created (`make setup-local`) |
| `AWS_SNS_ENDPOINT_OVERRIDE` | No | LocalStack endpoint (`http://localhost:4566`). Unset means the real AWS SNS endpoint is used, so leaving this unset locally without LocalStack running means every publish attempt fails — `SnsWorkspaceEventPublisher`'s caller fails open (logs, doesn't reject the request) |

**AWS dev environment** (set `JWT_PRIVATE_KEY_SSM_PATH` to activate this mode — requires AWS credentials):

| Variable | Required | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Yes | Injected from SSM at ECS task start |
| `SPRING_DATASOURCE_USERNAME` | Yes | Injected from SSM |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Injected from SSM |
| `JWT_PRIVATE_KEY_SSM_PATH` | Yes | SSM path to RS256 private key (`/collabspace/dev/auth/jwt-private-key`) |
| `JWT_ISSUER_SSM_PATH` | Yes | SSM path for JWT issuer string (`/collabspace/dev/jwt/issuer`) |
| `JWT_AUDIENCE_SSM_PATH` | Yes | SSM path for JWT audience string (`/collabspace/dev/jwt/audience`) |
| `JWT_JWKS_URI_SSM_PATH` | No | SSM path for JWKS URI (`/collabspace/dev/jwt/jwks-uri`) — defaults to `http://localhost:8080/.well-known/jwks.json` if unset |
| `INTERNAL_TOKEN_SSM_PATH` | Yes | SSM path to the shared internal-token secret (`/collabspace/dev/api/internal-token`), generated by Terraform and injected by API Gateway as `X-Internal-Token` on every forwarded request |
| `SPRING_DATA_REDIS_URL` | No | Upstash `rediss://` TLS URL, injected directly as an ECS task secret (not an SSM-path env var like the JWT_*/INTERNAL_TOKEN rows above) from `/collabspace/dev/redis/url` — see ADR-030 |
| `SNS_WORKSPACE_EVENTS_TOPIC_ARN` | Yes | Injected directly as an ECS task secret (same mechanism as `SPRING_DATASOURCE_PASSWORD`/`SPRING_DATA_REDIS_URL` above, not an SSM-path env var) from `/collabspace/dev/sns/workspace-events-topic-arn`, which Terraform populates with the `workspace-events` SNS topic's ARN — see ADR-037 |

Set variables via IntelliJ run configuration or an `.env` file (gitignored). See `.env.example` for a template.

## Database schema

Flyway runs migrations automatically on startup. Migration files live in `src/main/resources/db/migration/`.

| Version | File | What it does |
|---|---|---|
| V1 | `V1__create_users.sql` | Creates `users` table: `id UUID PK`, `name VARCHAR(255)`, `email VARCHAR(320)`, `password_hash TEXT` (nullable), `created_at`/`updated_at TIMESTAMPTZ` |
| V2 | `V2__name_email_constraint.sql` | Renames the email unique constraint from the Postgres auto-generated `users_email_key` to the explicit `users_email_unique` |
| V3 | `V3__create_refresh_tokens.sql` | Creates `refresh_tokens` table: `id UUID PK`, `user_id UUID FK → users.id ON DELETE CASCADE`, `token_hash TEXT UNIQUE`, `created_at TIMESTAMPTZ`, `expires_at TIMESTAMPTZ`, `user_agent TEXT` (nullable), `ip_address TEXT` (nullable). Includes index on `user_id`. |
| V4 | `V4__create_workspaces_and_memberships.sql` | Creates `workspaces` table: `id UUID PK`, `name VARCHAR(255)`, `description TEXT` (nullable), `created_by_user_id UUID FK → users.id`, `created_at`/`updated_at TIMESTAMPTZ`. Creates `workspace_memberships` table: `id UUID PK`, `workspace_id UUID FK → workspaces.id ON DELETE CASCADE`, `user_id UUID FK → users.id ON DELETE CASCADE`, `role VARCHAR(20)` (named `CHECK` constraint, `admin`\|`member`), `created_at`/`updated_at TIMESTAMPTZ`, named `UNIQUE(workspace_id, user_id)`. Includes index on `user_id`. |

## Deployment

Pushes to `main` that touch `services/auth-workspace/**` trigger `.github/workflows/service-auth.yml`, which:

1. Runs `./mvnw test`
2. Builds and pushes the Docker image to ECR (`collabspace-auth-workspace`) tagged `:<short-sha>`
3. Registers a new ECS task definition revision
4. Updates the `collabspace-dev-auth-workspace` ECS service and waits for stability

The service is reachable at the ALB DNS name once healthy:

```bash
cd infrastructure/environments/dev
terraform output alb_dns_name
```

## Architecture decisions

- [ADR-002](../../docs/06-decisions/adr-002-auth-workspace-combined.md) — auth and workspace combined into one service
- [ADR-012](../../docs/06-decisions/adr-012-terraform-cicd-task-definition-ownership.md) — Terraform owns initial task definition; CI/CD manages revisions
- [ADR-025](../../docs/06-decisions/adr-025-hexagonal-architecture.md) — hexagonal architecture (ports and adapters)
- [ADR-032](../../docs/06-decisions/adr-032-membership-claims-staleness-and-revocation.md) — membership claims staleness/revocation via `membership-changed-at` Redis marker + `X-JWT-Iat` comparison
- [ADR-033](../../docs/06-decisions/adr-033-loopback-health-probe-exemption.md) — loopback-only exemption for `/actuator/health/readiness`\|`liveness` from the internal-token check
- [ADR-037](../../docs/06-decisions/adr-037-separate-sns-topic-for-workspace-events.md) — separate `workspace-events` SNS topic for workspace domain events
- [ADR-038](../../docs/06-decisions/adr-038-pessimistic-locking-for-last-admin-invariant.md) — row-locking read for the last-admin invariant on member role changes
