# auth-workspace

Authentication and workspace management service. Handles user registration, login, JWT issuance, and workspace RBAC. Built with Java 25 + Spring Boot 4.

**Current state:** Registration and login endpoints live. Refresh tokens stored in Postgres. JWT issued on register (access token) and login (access token + HttpOnly refresh cookie). Redis client wired and reporting health (ADR-030). `JwtBlocklistFilter` checks the `jti` blocklist in Redis on every authenticated request (fail-open on Redis errors); the write side (setting a `jti` on logout) lands in a later PR.

Every request is additionally gated by three Spring Security filters — internal-token validation, identity-header population, and the JWT blocklist check. See "Trust model" below and [docs/03-services/auth-workspace/plans/security-filter.md](../../docs/03-services/auth-workspace/plans/security-filter.md).

## What it does

- `GET /actuator/health` — returns `{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},...}}`. Routed through API Gateway (public integration) — requires a valid `X-Internal-Token` but no JWT. Returns `503` with `status=DOWN` if the database is unreachable. `redis` reflects live Redis reachability but does not affect what gates traffic — see `/actuator/health/readiness` below.
- `GET /actuator/health/readiness` — scoped to `db` only. This is the traffic-readiness signal: an ECS container-level health check polls it, and ECS's HEALTHY/UNHEALTHY status feeds Cloud Map, which is what API Gateway routes against — see ADR-031. A DB outage stops traffic routing here without killing/restarting the container (restarting never fixes an external Neon outage). Has no API Gateway route at all — only reachable from loopback, and `InternalTokenFilter` exempts it from the token check only when both the path and the caller's raw socket address are loopback.
- `GET /actuator/health/liveness` — left at Spring Boot's default (no external dependency checks). Answers "should this process be killed and restarted," which only makes sense for internally-fixable brokenness — not used by anything yet. Same loopback-only token exemption as `/readiness`.
- `GET /.well-known/jwks.json` and `GET /.well-known/openid-configuration` — RS256 public key set and OIDC discovery document. **Must remain public** — called by API Gateway's own infrastructure (JWKS fetch, OIDC discovery), never routed through the VPC Link, never carries `X-Internal-Token`. Exempt from the token check unconditionally by path. See ADR-026.
- `POST /v1/auth/register` — public route. No JWT required. Returns `201` with access token and user summary on success; `400` for validation errors; `409` if the email is already registered.
- `POST /v1/auth/login` — public route. No JWT required. Returns `200` with access token and user on success; sets an HttpOnly `refresh_token` cookie (`Path=/auth`, `Max-Age=604800`, `Secure`, `SameSite=Strict`). Returns `400` for validation errors; `401` for invalid credentials.
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
| `X-Correlation-ID` | API Gateway `$context.requestId` | Overwritten by `CorrelationIdFilter` into MDC — runs before the security filter chain so `correlationId` is available in their rejection logs |

`X-User-Id`/`X-User-Workspaces` are expected to be absent on `/v1/auth/register` and `/v1/auth/login` (and the exempt routes above) — present there, they fail closed instead of being treated as harmless extra data, since it would mean the API Gateway header-stripping guarantee has regressed. The service must **not** re-validate the JWT signature. See [api-gateway-trust.md](../../docs/02-architecture/api-gateway-trust.md) and [the security-filter plan](../../docs/03-services/auth-workspace/plans/security-filter.md).

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

Set variables via IntelliJ run configuration or an `.env` file (gitignored). See `.env.example` for a template.

## Database schema

Flyway runs migrations automatically on startup. Migration files live in `src/main/resources/db/migration/`.

| Version | File | What it does |
|---|---|---|
| V1 | `V1__create_users.sql` | Creates `users` table: `id UUID PK`, `name VARCHAR(255)`, `email VARCHAR(320)`, `password_hash TEXT` (nullable), `created_at`/`updated_at TIMESTAMPTZ` |
| V2 | `V2__name_email_constraint.sql` | Renames the email unique constraint from the Postgres auto-generated `users_email_key` to the explicit `users_email_unique` |
| V3 | `V3__create_refresh_tokens.sql` | Creates `refresh_tokens` table: `id UUID PK`, `user_id UUID FK → users.id ON DELETE CASCADE`, `token_hash TEXT UNIQUE`, `created_at TIMESTAMPTZ`, `expires_at TIMESTAMPTZ`, `user_agent TEXT` (nullable), `ip_address TEXT` (nullable). Includes index on `user_id`. |

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
- [ADR-033](../../docs/06-decisions/adr-033-loopback-health-probe-exemption.md) — loopback-only exemption for `/actuator/health/readiness`\|`liveness` from the internal-token check
