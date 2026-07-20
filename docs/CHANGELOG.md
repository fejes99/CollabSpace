# CollabSpace Changelog

Project history. Each completed milestone is recorded here so `CLAUDE.md` Layer 2 can stay focused on the *current* stage rather than carrying the entire past.

New entries go at the top. Each entry names the stage, the date completed, and bullet points the artifacts.

---

## Stage 2 — Service Implementation (in progress, 2026-05)

### auth-workspace: list-workspaces (2026-07)

- `GET /v1/workspaces` — any authenticated user lists every workspace in the system (`id`, `name`, `memberCount`), cursor-paginated (`?limit=&after=`, opaque Base64-JSON cursor keyed on `createdAt`+`id`) per `api-conventions.md`'s pagination convention — the first implementation of that convention in the codebase. Deliberately **not** scoped to the caller's own memberships: the plan doc (`docs/03-services/auth-workspace/plans/list-workspaces.md` §7) documents this as a considered exception to `authorization.md`'s masking principle — workspace existence/name/count are treated as visible to any authenticated user, while contents and every mutating action remain exactly as membership-gated as before. No ADR (judged not to need one). A caller-scoped "my workspaces" variant is deferred to v1.5.
- `CursorCodec` (`adapter/in/rest/common/`) — generic Base64+JSON cursor encode/decode, reusable by future paginated endpoints; `WorkspaceCursor` is the thin typed wrapper for this endpoint's `(createdAt, workspaceId)` fields.
- `GlobalExceptionHandler` gained a `ConstraintViolationException` handler — `@RequestParam` constraints (`@Min`/`@Max`/`@ValidAfter`) on a `@Validated` controller throw a different exception type than `@Valid @RequestBody`'s `MethodArgumentNotValidException`, previously unhandled (fell through to a `500`). `handleTypeMismatch` now also distinguishes query params from path variables, a branch this handler never needed before this endpoint.
- Package reorg: `adapter/in/rest/workspace/` split into `request/`, `response/`, `validation/`; `application/port/in/workspace/` split into `command/`, `result/`, `usecase/` — both packages had grown past 10 flat files.
- 297 tests (unit: `WorkspaceCursorTest`, `CursorCodecTest`, `AfterValidatorTest`, 8 new `WorkspaceApplicationService.list()` cases; integration: `ListWorkspacesIntegrationTest`, 12 cases) — the integration tests surfaced a real cross-test isolation gap: this is the first endpoint in the service to query system-wide state rather than something scoped, so it's the first to be affected by other test classes (the concurrency tests, which can't use `@Transactional`) leaving committed rows in the shared Testcontainers database. Rewritten to assert on presence/relative order of self-created data rather than exact counts. Verified against the real running app locally (Docker + Testcontainers), including the error-response fixes above. Not yet verified on AWS.

### auth-workspace: create-workspace (2026-07)

- `POST /v1/workspaces` — any authenticated user creates a workspace and becomes its first admin; membership row inserted in the same transaction as the workspace row (`CommitThenAction`, ADR-034). Response reissues a fresh access token whose `memberships` claim reflects the new workspace immediately (ADR-032).
- Merged and verified end-to-end on AWS (2026-07-15) — verification surfaced two infra bugs, both fixed and documented:
  - [ADR-035](06-decisions/adr-035-paired-exact-and-proxy-api-gateway-routes.md): API Gateway `{proxy+}` routes can't match bare collection paths (`/v1/workspaces` with no trailing segment) — every resource now has a paired exact-path route alongside its `{proxy+}` route.
  - [ADR-036](06-decisions/adr-036-authorizer-claims-context-variable-syntax.md): the JWT-claim-to-header mapping used the wrong `$context.authorizer.jwt.claims.*` syntax; corrected to `$context.authorizer.claims.*` — meaning `X-User-Id`/`X-User-Workspaces`/`X-JWT-Jti` had never actually worked since PR #41/#42, undetected until this verification.

### auth-workspace: security-filter (2026-07)

- Three Spring Security filters, ordered `InternalTokenFilter` → `HeaderAuthenticationFilter` → `JwtBlocklistFilter`, run on every request ahead of the eventual `@PreAuthorize` work: `InternalTokenFilter` validates `X-Internal-Token` (SSM-backed in AWS, `.env`-backed locally); `HeaderAuthenticationFilter` populates `SecurityContextHolder` from `X-User-Id`/`X-User-Workspaces` as a `PreAuthenticatedAuthenticationToken`, fail-closed on malformed/unexpected headers; `JwtBlocklistFilter` checks `X-JWT-Jti` against a Redis-backed blocklist (`TokenBlocklistRepository`/`TokenBlocklistRedisAdapter`), fail-open if Redis is unreachable.
- `ProblemDetailsSecurityHandler` renders all rejections as RFC 9457 Problem Details.
- `SecurityExemptPaths` — shared path-exemption class (`.well-known/**`, loopback-only health probes, local Swagger/OpenAPI tooling) so the two path-dependent filters can't drift apart; boundary-checked (`/swagger-uikit-asset` must not match `/swagger-ui`).
- Package reorg: `adapter/in/rest/security/` split into `exception/` and `filter/` subpackages.
- Terraform: `INTERNAL_TOKEN_SSM_PATH` wired into `auth-workspace`'s ECS task definition, applied live to AWS dev.
- Swagger `apiKey` security schemes for local "Try it out"; register/login scoped to just `X-Internal-Token` so Swagger's Authorize dialog can't attach headers those routes reject.
- `jti` added to the `event=user_registered` and `event=user_logged_in` audit log lines (`JwtService.issueAccessToken` now returns `AccessToken(token, jti)` instead of a bare string) — closes the token-lifecycle traceability gap (issue → blocklist write → blocklist hit) for tokens minted at either registration or login.
- Found and fixed: `RegisterTransactionalIT.java` used the Failsafe `*IT.java` naming convention, but this project has no Failsafe plugin configured — only Surefire, which doesn't match that pattern. It had never actually run, in CI or locally. Renamed to `RegisterTransactionalIntegrationTest.java` and fixed its missing `X-Internal-Token` header.
- 101 tests, mutation-tested throughout (deliberately broke each check, confirmed the intended test failed, reverted).

### auth-workspace: db-connection (2026-05)

- `spring-boot-starter-jdbc` + `postgresql` driver added; datasource config reads `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` from environment. Neon PostgreSQL (SSL required: `sslmode=require`).
- HikariCP `initialization-fail-timeout=10000` — service refuses to start if DB is unreachable at boot.
- `DbHealthIndicator` — custom `HealthIndicator` replacing Spring Boot auto-configured `db` component. Stateful: logs `event=db.health.down` / `event=db.health.recovered` on transitions only; no log noise on every poll. Validates connections with `isValid(1)`. Host extracted at construction time — full JDBC URL never logged.
- `management.endpoint.health.show-components=always` — `/actuator/health` now returns per-component status (`components.db.status`).
- `TestContainersConfiguration` — shared `@TestConfiguration` with `@Bean @ServiceConnection PostgreSQLContainer` using Spring Boot 4 + Testcontainers 2.0 native integration.
- `GlobalExceptionHandlerTest` converted from `@SpringBootTest` to `@WebMvcTest` (web layer only — no datasource needed).
- 2 new integration tests: `HealthCheckIntegrationTest` (UP + component key), `HealthCheckDownIntegrationTest` (503 via invalid URL + fast HikariCP timeout).

### auth-workspace: service baseline (2026-05)

- `CorrelationIdFilter` — reads or generates `X-Correlation-ID`, stores in MDC, echoes in response headers (`X-Correlation-ID`, `Access-Control-Expose-Headers`). MDC cleared in `finally` to prevent thread-pool leakage.
- `GlobalExceptionHandler` — `@RestControllerAdvice` mapping all unhandled exceptions to RFC 9457 Problem Details (`type=about:blank`, `status=500`). Internal exception messages never forwarded to caller.
- Logback JSON config — human-readable pattern on `local` profile; `LogstashEncoder` JSON on all other environments. `correlationId` from MDC appears automatically in every log line.
- Spring Java Format — `spring-javaformat-maven-plugin` bound to `validate` phase; IntelliJ plugin applies on save.
- 9 tests: 4 × `CorrelationIdFilterTest`, 4 × `GlobalExceptionHandlerTest`, 1 × context load. Test packages mirror hexagonal structure (`adapter/in/rest`).

---

## Stage 1 — Walking Skeleton (complete, 2026-05)

All five services deployed to AWS dev environment and reachable via the ALB.

- `auth-workspace` — Spring Boot 4.0.6 + Java 25; `/actuator/health` → 200 OK from ECS Fargate.
- `document-service` — Node.js 24 + TypeScript + Fastify 5; `/documents/health` → 200 OK from ECS Fargate.
- `realtime-service` — Node.js 24 + TypeScript + Fastify 5; `/realtime/health` → 200 OK from ECS Fargate.
- `ai-assistant` — Python 3.14 + FastAPI; `/assistant/health` → 200 OK from ECS Fargate.
- `notification` — Node.js 24 Lambda (ZIP deploy, no Docker — see [ADR-023](06-decisions/adr-023-lambda-zip-deployment.md)); `/notifications/health` → 200 OK via ALB.

CI/CD pipelines per service (`.github/workflows/service-*.yml`): test → build → ECR push (or zip for Lambda) → deploy → wait for stability. Path-filtered so each pipeline only fires on its service's changes.

### Stage 1 infrastructure

- `infrastructure/environments/dev/main.tf` — VPC (`10.0.0.0/16`), 2 public + 2 private subnets across `eu-central-1a/b`, IGW + public route table + S3 gateway endpoint, ALB SG / ECS tasks SG / RDS SG, IAM (shared ECS task execution role + per-service task roles), CloudWatch log groups (7-day retention), ECS cluster with Container Insights disabled (see [ADR-011](06-decisions/adr-011-container-insights-dev.md)), internet-facing ALB.
- Reusable Terraform modules: `modules/vpc/`, `modules/security-groups/`, `modules/iam-ecs/`, `modules/cloudwatch/`, `modules/ecs-cluster/`, `modules/alb/`, `modules/ecs-service/`, `modules/lambda-function/`.
- Service listener rules at priorities: notification 20, ai-assistant 30, realtime 40, document 50. Path prefixes per service.

---

## Stage 0 — Foundation (complete, 2026-04)

- `infrastructure/bootstrap/` — applied to real AWS. S3 state bucket + DynamoDB lock table + billing alarm live. Local state only (see [ADR-006](06-decisions/adr-006-terraform-bootstrap-state.md)).
- `infrastructure/shared/` — 4 ECR repos + GitHub Actions OIDC provider + CI IAM role live. S3 remote state (see [ADR-007](06-decisions/adr-007-github-actions-oidc-auth.md)). `collabspace-ecs-deploy` IAM policy permits CI to register task definitions and update ECS services. `lambda-deploy` IAM policy permits CI to call `lambda:UpdateFunctionCode` and `GetFunction` on dev functions.
- ADRs adr-001 through adr-022 — see [docs/06-decisions/](06-decisions/).

---

## How to read this changelog

- The most recent stage / completion is at the top.
- Each entry is dated to roughly the month of completion.
- ADRs are linked inline where they explain a non-obvious choice; the full ADR list lives in [docs/06-decisions/](06-decisions/).
- For an active session, see `CLAUDE.md` Layer 2 for what's *currently* in progress.
