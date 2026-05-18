# auth-workspace

Authentication and workspace management service. Handles user registration, login, JWT issuance, and workspace RBAC. Built with Java 25 + Spring Boot 4.

**Current state:** Service baseline complete — structured JSON logging, correlation ID filter, and global exception handler are wired and tested. Database connection and full auth endpoints are not yet implemented (next: `feat/auth/db-connection`).

## What it does

- `GET /actuator/health` — returns `{"status":"UP"}`. Public route — no JWT required.
- `GET /.well-known/jwks.json` — RS256 public key set. **Must remain a public route** — the API Gateway JWT Authorizer fetches signing keys from this URL. See ADR-026.
- `POST /auth/register` — public route. No JWT required.
- `POST /auth/login` — public route. No JWT required.
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
│   ├── model/              Pure Java records. No Spring, no JPA annotations.
│   └── exception/          Domain exceptions (e.g. EmailAlreadyTakenException).
│
├── application/
│   ├── port/
│   │   ├── in/             Use case interfaces — one per operation.
│   │   │   ├── RegisterUserUseCase.java
│   │   │   ├── LoginUseCase.java
│   │   │   └── CreateWorkspaceUseCase.java
│   │   └── out/            Outbound port interfaces.
│   │       ├── UserRepository.java
│   │       ├── WorkspaceRepository.java
│   │       └── TokenBlocklistPort.java
│   └── service/            Application services: implement in-ports, call out-ports.
│       ├── AuthApplicationService.java
│       └── WorkspaceApplicationService.java
│
└── adapter/
    ├── in/
    │   └── rest/           REST controllers, request/response DTOs, cross-cutting filters.
    │       ├── AuthController.java
    │       ├── WorkspaceController.java
    │       ├── CorrelationIdFilter.java
    │       ├── GlobalExceptionHandler.java
    │       └── dto/
    │           ├── RegisterRequest.java
    │           └── RegisterResponse.java
    └── out/
        ├── persistence/    JPA entities + Spring Data implementations of out-ports.
        │   ├── UserJpaAdapter.java
        │   ├── entity/
        │   │   └── UserEntity.java
        │   └── repository/
        │       └── UserJpaRepository.java
        ├── redis/          Redis adapter implementing TokenBlocklistPort.
        │   └── RedisTokenBlocklistAdapter.java
        └── ssm/            SSM adapter for secrets loaded at startup.
            └── SsmConfigLoader.java
```

**Dependency rule:** dependencies point inward only — `adapter` → `application` → `domain`. Nothing in `domain/` or `application/` imports from `adapter/`.

## Running locally

```bash
cd services/auth-workspace
./mvnw spring-boot:run
```

The service starts on port 8080.

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

| Variable | Required | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Stage 2+ | Injected from SSM at ECS task start |
| `SPRING_DATASOURCE_USERNAME` | Stage 2+ | Injected from SSM |
| `SPRING_DATASOURCE_PASSWORD` | Stage 2+ | Injected from SSM |
| `JWT_PRIVATE_KEY_SSM_PATH` | Stage 2+ | SSM path to RS256 private key |
| `REDIS_URL` | Stage 2+ | Upstash TLS URL, injected from SSM |

For local development, set these in a `.env` file (gitignored). None are required for the service-baseline PR.

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
