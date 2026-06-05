# auth-workspace

Authentication and workspace management service. Handles user registration, login, JWT issuance, and workspace RBAC. Built with Java 25 + Spring Boot 4.

**Current state:** UserJpaAdapter, user registration, and local-dev JWT configuration complete. `POST /v1/auth/register` is live. Login endpoint not yet implemented.

## What it does

- `GET /actuator/health` — returns `{"status":"UP","components":{"db":{"status":"UP"},...}}`. Public route — no JWT required. Returns `503` with `status=DOWN` if the database is unreachable.
- `GET /.well-known/jwks.json` — RS256 public key set. **Must remain a public route** — the API Gateway JWT Authorizer fetches signing keys from this URL. See ADR-026.
- `POST /v1/auth/register` — public route. No JWT required. Returns `201` with access token and user summary on success; `400` for validation errors; `409` if the email is already registered.
- `GET /v3/api-docs` — OpenAPI 3.x JSON spec (internal tooling only, not routed through API Gateway).
- `GET /swagger-ui.html` — interactive Swagger UI for local development.

All responses include `X-Correlation-ID`. All errors return RFC 9457 Problem Details (`Content-Type: application/problem+json`).

### Trust model

Requests arriving from API Gateway carry three injected headers:

| Header | Source | Purpose |
|---|---|---|
| `X-Internal-Token` | API Gateway stage variable | Validate on every request — reject 401 if missing or wrong |
| `X-User-Id` | JWT `userId` claim | Authenticated user identity |
| `X-User-Workspaces` | JWT `memberships` claim | Workspace memberships for authorization |
| `X-Correlation-ID` | API Gateway `$context.requestId` | Overwritten by `CorrelationIdFilter` into MDC |

The service must **not** re-validate the JWT signature. See [api-gateway-trust.md](../../docs/02-architecture/api-gateway-trust.md).

The JWT issuer (`iss`) and audience (`aud`) the service sets in issued tokens are read from SSM at startup:
- `/collabspace/dev/jwt/issuer` → `https://auth.dev.collabspace.io`
- `/collabspace/dev/jwt/audience` → `collabspace-api`

## Project structure

Hexagonal Architecture (Ports and Adapters) — see [ADR-025](../../docs/06-decisions/adr-025-hexagonal-architecture.md) for the rationale.

```
src/main/java/com/collabspace/authworkspace/
│
├── domain/
│   ├── model/
│   │   └── auth/           Pure Java records. No Spring, no JPA annotations.
│   │       └── User.java
│   └── exception/          Cross-cutting domain exceptions.
│       ├── DomainException.java
│       ├── ConflictException.java
│       ├── EmailAlreadyTakenException.java
│       └── NotFoundException.java
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── auth/       Use case interfaces — one per operation.
│   │   │       ├── RegisterUserUseCase.java
│   │   │       └── LoginUseCase.java          (future)
│   │   └── out/
│   │       └── auth/       Outbound port interfaces.
│   │           ├── UserRepository.java
│   │           └── TokenBlocklistPort.java    (future)
│   └── service/
│       ├── auth/           Application services: implement in-ports, call out-ports.
│       │   └── AuthApplicationService.java
│       └── JwtService.java
│
├── config/                 Spring configuration and startup components.
│   ├── ApplicationConfig.java
│   ├── OpenApiConfig.java
│   └── SwaggerStartupListener.java
│
└── adapter/
    ├── in/
    │   └── rest/
    │       ├── auth/       Auth controllers and DTOs.
    │       │   ├── AuthController.java
    │       │   ├── RegisterRequest.java
    │       │   └── RegisterResponse.java
    │       ├── error/      RFC 9457 global exception handler.
    │       ├── wellknown/
    │       │   └── WellKnownController.java
    │       │   └── GlobalExceptionHandler.java
    │       ├── filter/
    │       │   └── CorrelationIdFilter.java
    │       ├── health/
    │       │   └── DbHealthIndicator.java
    │       └── security/
    │           └── SecurityConfig.java
    └── out/
        ├── persistence/
        │   └── auth/       JPA entities + Spring Data repository.
        │       ├── UserJpaAdapter.java
        │       ├── entity/
        │       │   └── UserEntity.java
        │       └── repository/
        │           └── UserJpaRepository.java
        └── ssm/            JWT key loading from SSM (AWS) or env var (local).
            ├── JwtKeyConfig.java
            └── LocalJwtConfig.java
```

**Dependency rule:** dependencies point inward only — `adapter` → `application` → `domain`. Nothing in `domain/` or `application/` imports from `adapter/`.

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
| `REDIS_URL` | Yes | Upstash TLS URL, injected from SSM |

Set variables via IntelliJ run configuration or an `.env` file (gitignored). See `.env.example` for a template.

## Database schema

Flyway runs migrations automatically on startup. Migration files live in `src/main/resources/db/migration/`.

| Version | File | What it does |
|---|---|---|
| V1 | `V1__create_users.sql` | Creates `users` table: `id UUID PK`, `name VARCHAR(255)`, `email VARCHAR(320)`, `password_hash TEXT` (nullable), `created_at`/`updated_at TIMESTAMPTZ` |
| V2 | `V2__name_email_constraint.sql` | Renames the email unique constraint from the Postgres auto-generated `users_email_key` to the explicit `users_email_unique` |

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
