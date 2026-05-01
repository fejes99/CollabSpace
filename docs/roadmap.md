# CollabSpace — Project Roadmap

CollabSpace is built in stages, each with a clear done condition. This document defines the scope of what is being built: which engineering concerns belong in the first working system (MVP), which are deferred to a follow-on phase (v1.5), and which are explicitly out of scope. It is a scope contract, not a timeline. Downstream architecture documents and ADRs reference it when justifying what is and is not included in a design.

The roadmap does not describe _how_ concerns are implemented — that lives in service-specific docs and ADRs. It describes _what_ is required and _when_, so that any engineering decision made during implementation can be evaluated against a stable definition of scope.

---

## Concern categories

Engineering work on CollabSpace is organized into four categories. The distinction matters because each category activates at a different point in the build and requires a different kind of planning.

**Category A — Per-service concerns** are things every service implements independently, from a shared playbook. Health checks, input validation, structured logging, and error handling each have a standard approach that is documented once and applied uniformly as each service is scaffolded. The pattern is centralized; the implementation is not. This is different from cross-cutting concerns: a service's input validation logic lives inside that service, even if every service's validation approach looks the same.

**Category B — Cross-cutting platform concerns** are implemented once at the platform level and consumed by all services. Authentication and authorization are the primary examples: JWT validation runs at API Gateway, RBAC enforcement runs inside each service, and both depend on a token format and key management strategy that is defined once. Changing a cross-cutting concern means touching every service — which is why the design decisions behind them (see [authentication.md](02-architecture/authentication.md) and [authorization.md](02-architecture/authorization.md)) are documented more carefully than per-service concerns.

**Category C — Data layer concerns** govern how each service interacts with its persistent store. They are per-service in implementation — each service owns its schema, its migration tool, and its query patterns — but the decisions about _which_ concerns to address and _when_ are made at the platform level. Speculative indexing before query patterns are understood is waste; missing a migration strategy before the first schema change is a production incident waiting to happen.

**Category D — Operational concerns** govern system behavior under load, failure, and change. They depend on the system existing and running under real traffic patterns. Autoscaling, blue/green deployments, and disaster recovery are real engineering work, but they cannot be done well before the system is observable and the normal operating envelope is understood. At this stage of the project, Category D is explicitly out of scope.

---

## MVP

The MVP is the first version of CollabSpace that is deployed to AWS, observable, and secure. It is not a minimum demo — every service ships with the full per-service baseline, authentication is real, and the async event pipeline is operational. The bar for MVP is: a user can create a workspace, invite members, collaborate on a document, and receive a notification, and the system behaves correctly and securely throughout.

### Category A — Per-service baseline

Every service that ships in the MVP implements the following, in this order of priority:

- **Entry point and graceful shutdown.** The application starts cleanly from a single bootstrap entry point and handles `SIGTERM` with a drain window before exit. On ECS Fargate, task replacement sends `SIGTERM` before `SIGKILL`; services that do not handle it drop in-flight requests silently. This is the first thing implemented when a service is scaffolded, not the last.
- **Health checks.** A dedicated health endpoint (`/actuator/health` for Spring Boot, `/health` for Node and Python services) that returns `200 OK` when the service and its dependencies are ready. The ALB uses this endpoint for target group health; ECS uses it for task readiness. A hand-rolled `/health` returning a static `200` is a learning shortcut — the production-grade approach verifies the database connection on each check. When in doubt, use the framework's built-in mechanism.
- **Input validation.** All external input (request bodies, query parameters, path variables) is validated at the service boundary — at the controller or route handler layer, before it reaches service logic. The validation library is language-specific: Bean Validation (`jakarta.validation`) for Java, zod for TypeScript, Pydantic for Python. Validation errors return a structured error response (see [api-conventions.md](02-architecture/api-conventions.md)).
- **Structured logging with correlation IDs.** Every log line is JSON, not a human-readable string. Every request generates a correlation ID at the entry point — or reads one propagated from the caller — and attaches it to every log line emitted during that request's lifecycle. The correlation ID is what makes a distributed system debuggable. Not including it from day one means retrofitting it later, which means touching every log statement across every service.
- **Error handling.** Unhandled exceptions do not return stack traces to callers. Each service has a top-level error handler that maps exceptions to RFC 9457 Problem Details responses (see [api-conventions.md](02-architecture/api-conventions.md)). A service that leaks stack traces in production responses is a security vulnerability; a service that returns inconsistent error formats makes client-side error handling brittle.
- **OpenAPI specification.** Each service's API contract is documented in an OpenAPI spec, auto-generated from annotations where the framework supports it (Spring Boot with Springdoc, FastAPI natively). The spec is the contract between the service and its callers; it must reflect what the service actually does, not what was planned.

### Category B — Cross-cutting platform concerns

- **Authentication and authorization.** JWT validation runs at API Gateway; each service enforces workspace-scoped RBAC from claims in the validated token. The token format, signing algorithm, and key management strategy are documented in [authentication.md](02-architecture/authentication.md) and [authorization.md](02-architecture/authorization.md). These are MVP because every protected endpoint depends on them — they cannot be deferred without leaving the system open.
- **Secrets management via SSM Parameter Store.** No service reads secrets from environment variables set at deployment time. All secrets (database credentials, signing keys, API keys) are read from AWS SSM Parameter Store at startup. The alternative — baking secrets into task definitions or CI environment config — creates security debt that is difficult to unwind and an audit trail that is impossible to reconstruct.
- **Service-to-service authentication.** The AI Assistant calls the Document Service with a short-lived signed JWT (5-minute TTL) to prove its identity. This is the only internal call that requires authentication in v1; the pattern is documented in [ADR-014](06-decisions/adr-014-service-to-service-auth.md).
- **Log aggregation via CloudWatch Logs Insights.** Structured logs from all services flow to CloudWatch. Logs Insights provides ad-hoc querying across services without additional infrastructure. This is not best-in-class observability — see v1.5 for distributed tracing — but it is sufficient to debug production issues and it is free at the log volumes expected in a development environment. Logs that are not aggregated are not useful.
- **Dead letter queue handling.** Every SQS subscription has a DLQ configured. When a message cannot be processed after the retry threshold, it lands in the DLQ rather than disappearing silently. The notification service and realtime service both consume from SQS; messages that fail silently in these services mean users miss notifications or see stale document state. DLQ handling does not mean automated recovery — it means visibility into failure.

### Category C — Data layer

- **Database migrations.** Each service that owns a relational schema manages migrations with a dedicated tool (Flyway for Java/Spring Boot; the equivalent for other services will be decided per-service). Migrations run at service startup or as a CI step, never manually. The migration tool is the source of truth for schema state; the database is not.
- **Pagination.** All list endpoints that return more than a single entity use cursor-based pagination. Offset pagination (`LIMIT x OFFSET y`) is simpler to implement but performs increasingly poorly as the dataset grows and produces inconsistent results under concurrent writes. Cursor-based pagination using an opaque cursor (encoding a `(timestamp, id)` tuple) is the standard approach for a system that will grow. This is MVP because retrofitting pagination after clients have integrated against a non-paginated endpoint is a breaking API change.
- **Indexing as-needed.** Indexes are added when query patterns are understood, not speculatively. The migration tool adds and tracks index definitions. No index is added without a corresponding slow query or measured access pattern justifying it.

---

## v1.5

These concerns are real but are deferred because implementing them before the system has real traffic patterns would produce designs that optimize for the wrong thing. Each has a clear trigger condition — the point at which deferral stops being prudent and starts being negligent.

- **Rate limiting at API Gateway.** API Gateway supports usage plans and rate limits natively. This is deferred because rate limiting requires knowing the expected request rates per endpoint and per client before limits can be set correctly. Arbitrary limits applied without data produce false positives that block legitimate use. _Trigger: when the first external-facing feature is deployed and real usage patterns are observable._
- **Distributed tracing.** CloudWatch Logs Insights provides correlation-ID-based log search across services, which covers the majority of debugging needs. Distributed tracing (OpenTelemetry → X-Ray or a compatible backend) adds span-level timing and a visual call graph — useful when performance issues cross service boundaries. The overhead of instrumenting every service is significant and the payoff is low when most traffic is local development. _Trigger: when a performance issue requires tracing a request across more than two services._
- **Metrics dashboards.** Per-service dashboards showing request count, error rate, and latency percentiles require stable traffic patterns to be meaningful. _Trigger: when at least one service has completed MVP feature implementation and is receiving sustained traffic._
- **Caching.** Redis (Upstash) is already in the architecture for auth token management (refresh token storage, revocation blocklist). Caching application data — document metadata, workspace member lists — is deferred until a measured hot path justifies it. Premature caching adds cache invalidation complexity without a demonstrable benefit. _Trigger: a specific endpoint measured at > 100ms median latency with a cacheable, bounded result set._
- **Idempotency keys.** Write operations (document creation, workspace creation) should be idempotent when clients retry on network failure. The pattern — client sends `Idempotency-Key` header; server deduplicates by key within a TTL — is documented in [api-conventions.md](02-architecture/api-conventions.md) and deferred to v1.5. _Trigger: before the first integration with any client that retries on failure without user interaction._

---

## Out of scope

These items are excluded from both MVP and v1.5. They represent real production engineering concerns, but none of them is appropriate at this stage. Where the exclusion carries a meaningful learning note, it is included.

- **RDS Multi-AZ.** Multi-AZ failover is a high-availability concern. In a development environment running at minimum viable cost, a single-AZ RDS instance is appropriate. Multi-AZ belongs in a production environment with an SLA.
- **Autoscaling.** ECS service autoscaling requires understanding the relationship between load and resource consumption. That relationship cannot be known before the service has processed real traffic under realistic conditions. Configuring autoscaling before then is guesswork.
- **Blue/green and canary deployments.** Relevant once multiple clients depend on the service and zero-downtime deploys become a contractual requirement. At this scale, a rolling restart is sufficient.
- **Disaster recovery.** RDS automated backups are enabled by default. A formal DR strategy — RPO/RTO targets, documented recovery procedures, tested restore processes — belongs in a production operations runbook, not a walking skeleton.
- **ABAC (attribute-based access control).** Workspace-scoped RBAC with two roles covers all v1 use cases. ABAC — per-document permissions, row-level policies, conditional access — adds significant authorization complexity and is not required by any feature described in this version of the product. → See [authorization.md](02-architecture/authorization.md).
- **OAuth2 social login (Google, GitHub, etc.).** Implementing JWT-based auth manually is an explicit learning goal of this project. Social login would delegate the most instructive parts of the auth flow to a third party. → See [ADR-002](06-decisions/adr-002-auth-workspace-combined.md).
- **SAML and enterprise SSO.** Not in the product scope.
- **MFA.** A well-designed extension to the auth service, but not required for v1. The token and session model are compatible with MFA; adding it is a future decision, not a fundamental rethink.
- **API tokens for external clients.** Machine-to-machine tokens (long-lived API keys for third-party integrations) are not a v1 concern.
- **Generic scalability work.** "Make it scale" is not an engineering task without a measured bottleneck. All scalability-motivated work is deferred until a specific performance issue is identified and reproduced.
- **Audit logging.** A compliance-grade audit trail — who did what, to what, when, stored durably and tamper-evidently — is noted in [authentication.md](02-architecture/authentication.md) as a future concern. Structured logs provide a soft audit trail sufficient for debugging; a formal audit log is a separate engineering investment.

---

## Stage delivery sequence

Stage 1 is the Walking Skeleton: five services deployed to AWS, each reachable and healthy, CI/CD running, and the observability baseline in place. No inter-service communication, no authentication logic, no real feature code. The done condition for Stage 1 is: five health endpoints return `200 OK` from inside AWS, deployed by GitHub Actions.

Stage 1 proceeds in six steps:

1. **Shared infrastructure** _(complete)._ VPC, ECR repositories, IAM roles, security groups, CloudWatch log groups, ECS cluster, ALB, and the ECS service module. The foundation every subsequent step depends on.
2. **First service skeleton — auth-workspace** _(in progress)._ A Spring Boot application with one endpoint: `GET /actuator/health → 200 OK`. Docker multi-stage build, image pushed to ECR with the `:skeleton` tag, ECS task stabilized and reachable at the ALB DNS name.
3. **CI/CD pipeline — auth-workspace.** GitHub Actions workflow: lint → test → Docker build → ECR push → ECS force-new-deployment. Triggered on push to `main`. Once working on the first service, every subsequent service is a configuration addition, not a new engineering problem.
4. **Remaining service skeletons.** document-service (TypeScript/Express), realtime-service (TypeScript/ws), AI assistant (Python/FastAPI), notification (Node.js Lambda). Each slots into the existing CI/CD pattern. Realtime is the exception: ECS-on-EC2 rather than Fargate, and the health signal is a WebSocket echo rather than an HTTP endpoint.
5. **Routing layer.** API Gateway routes wired to all services. ALB listener rule for WebSocket upgrade. SNS → SQS → Lambda subscription for notification. Each route verified from the public internet.
6. **Observability baseline.** Structured JSON logs flowing from all services to CloudWatch. One CloudWatch dashboard per service showing request count, error count, and memory usage.

After Stage 1, services are implemented in this order: auth-workspace first (Java/Spring Boot has the longest cold start and most complex build — front-load the hard parts), then document-service, then realtime-service, then AI assistant, then notification. As each service is implemented, the per-service MVP concerns (Category A) are applied in full before moving to the next. Cross-cutting concerns (Category B) are implemented once during auth-workspace and consumed by each subsequent service without re-design.
