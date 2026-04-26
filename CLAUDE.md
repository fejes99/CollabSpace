# CollabSpace — Claude Code Project Context

## SESSION OPENER

Before doing anything in a new session: confirm you've read this file by stating
back to me (1) the current stage from Layer 2, (2) the current goal, and (3)
what's explicitly out of scope this session. Then ask what specifically I want
to work on. Do not start generating files until I confirm.

## LAYER 1: STABLE CONTEXT

### What this is

A learning project: 5-service collaboration platform on AWS.
Junior-to-medior level developer. The goal is learning, not shipping fast.
See /docs for full architecture once those files exist.

### Architecture summary

- Auth & Workspace: Java 21 + Spring Boot 3, PostgreSQL (RDS), Redis (Upstash)
- Document Service: Node.js 22 + TypeScript + Express, MongoDB Atlas
- Realtime Service: Node.js 22 + TypeScript + ws, Redis pub/sub coordination
- AI Assistant: Python 3.13 + FastAPI, Postgres + pgvector
- Notification: AWS Lambda (Node.js 22)

### Communication

- Sync: REST via API Gateway (HTTP API), WebSocket via ALB
- Async: SNS+SQS for fan-out events; self-managed Kafka in EC2 for AI events

### Infrastructure

- AWS account, free-tier maximalist (~$0–5/month target)
- Terraform with module-per-concept structure
- GitHub Actions with OIDC auth, no long-lived credentials
- LocalStack for local AWS emulation
- Compute mix: ECS Fargate (Auth, Document, AI) + EC2 (Realtime, Kafka)
  - Lambda (Notification)

### Repository structure (monorepo)

```
/services/
  /auth-workspace/    — Java + Spring Boot (Stage 1+)
  /document-service/  — TypeScript + Express (Stage 1+)
  /realtime-service/  — TypeScript + ws (Stage 1+)
  /ai-assistant/      — Python + FastAPI (Stage 1+)
  /notification/      — Node.js Lambda (Stage 1+)
/infrastructure/
  /modules/           — reusable Terraform modules
  /environments/dev/  — dev environment composition
  /bootstrap/         — one-time state backend setup
  /shared/            — ECR repos, OIDC provider
/docs/
  /01-overview/       — vision, use-cases, glossary
  /02-architecture/   — system overview, tech choices, communication
  /03-services/       — per-service docs (added as services are built)
  /04-infrastructure/ — AWS arch, networking, cost strategy
  /05-cicd/           — pipeline overview, deployment strategy
  /06-decisions/      — ADRs (numbered: adr-001-*.md, adr-002-*.md, ...)
  /07-development/    — local setup, coding standards, testing strategy
  /08-operations/     — monitoring, runbooks (added later)
/.github/workflows/   — CI/CD pipelines
```

### Hard rules

- BEFORE generating any non-trivial file, propose what you're about to create
  and why. Wait for approval. This is a learning project — the discussion is
  more valuable than the file itself.
- When you DO generate a file, walk me through the key decisions in it AFTER
  generation, before I commit. I should not commit anything I cannot explain.
- Never push to main directly; always feature branches + PRs.
- Never run `terraform apply` without showing the plan first and getting
  explicit approval.
- Never commit .env files, secrets, or AWS credentials.
- For changes touching > 3 files, propose a plan and wait for approval.
- Cite the relevant ADR when making non-trivial choices. If no ADR exists for
  a decision you're about to make, say so and offer to write one.
- Idiomatic per language: Pythonic Python, Spring conventions for Java,
  modern TypeScript with strict mode for Node/Express.

### Code style

Java (Spring Boot):

- Constructor injection only (no @Autowired on fields)
- Records for DTOs
- Optional<T> over null returns
- @Transactional on service methods that span multiple repository calls
- Bean Validation (jakarta.validation) at controller boundary

TypeScript (Express):

- strict: true in tsconfig
- Named exports only (no default exports)
- zod for runtime validation, infer types from schemas
- pino for logging, never console.log
- async/await, never raw promises
- No `any` without comment justifying

Python (FastAPI):

- Type hints on every public function
- Pydantic models for request/response
- async def for I/O-bound code
- structlog for logging
- ruff for lint, black for format

Terraform:

- snake_case for resource names
- All resources tagged (Environment, Service, ManagedBy=terraform)
- for_each over count
- Module per concept (not per service)

### Library policy

- New dependency requires ADR justification when stdlib or an existing
  dependency could do the job.
- Heavy DI containers in TypeScript: prefer manual DI / awilix over
  inversify or tsyringe at this scale.
- ORMs in TypeScript: Mongoose for Mongo, no second ORM.
- Avoid: Lombok in Java (records cover most cases), moment.js (use date-fns
  or native Intl), heavy auth libs when learning JWT manually is the goal.

### Secrets and config

- Local dev: .env files (in .gitignore), loaded by service at startup
- Deployed: SSM Parameter Store (NOT Secrets Manager — see ADR on cost)
- Reference pattern in code: read SSM path, never hardcode value
- Never log secret values, even at DEBUG level

### Definition of Done (per service feature)

- Unit tests + at least one integration test
- OpenAPI spec updated (auto-generated where possible)
- README covers what changed
- Deployed via CI/CD to AWS dev environment
- Observable: structured logs with correlation ID
- ADR written if a non-trivial decision was made

### ADR conventions

- Filename: `adr-NNN-kebab-case-title.md` (zero-padded: adr-001, adr-002, ...)
- Required sections: Status, Date, Context, Decision, Alternatives Considered,
  Consequences (with both + and − bullets), Revisit when
- Status values: Proposed | Accepted | Superseded by ADR-NNN | Deprecated
- Write at decision time, not later
- Adversarial review before committing: ask Claude Code to poke holes in the
  decision first, then revise, then commit.

### Test/build commands

- Java: `./mvnw test`, `./mvnw package -DskipTests`
- TypeScript: `npm run test`, `npm run build`, `npm run lint`
- Python: `pytest`, `ruff check .`
- Terraform: `terraform fmt`, `terraform validate`, `terraform plan`

## LAYER 2: CURRENT FOCUS

Current stage: Stage 1 — Walking Skeleton
Current service: auth-workspace (next to scaffold)
Current goal: ECS cluster + ALB + auth-workspace container reachable via HTTP in dev, deployed by GitHub Actions CI

Out of scope next session: full service implementation, databases, inter-service communication. Walking Skeleton = one service reachable via HTTP in AWS dev, deployed by CI. Nothing more.

Blocked on: nothing
Recent ADRs: adr-001 to adr-012

Completed:
- infrastructure/bootstrap/ — applied to real AWS; S3 state bucket + DynamoDB lock table + billing alarm live
- infrastructure/shared/ — applied to real AWS; 4 ECR repos + GitHub Actions OIDC provider + CI IAM role live
- infrastructure/environments/dev/ — applied to real AWS; shared network foundation live:
  - VPC (10.0.0.0/16), 2 public subnets + 2 private subnets across eu-central-1a/b
  - Internet Gateway + public route table + S3 gateway endpoint (free ECR layer routing)
  - Security groups: ALB, ECS tasks, RDS (no Redis SG — Upstash is external SaaS)
  - IAM: shared ECS task execution role + per-service task roles for 4 ECS services
  - CloudWatch log groups for all 5 services with 7-day retention
- modules/ecs-cluster/ — ECS cluster with Container Insights toggle (disabled in dev; see ADR-011)
- modules/alb/ — internet-facing ALB + HTTP listener with fixed-response default; services plug in via listener rules
- modules/ecs-service/ — generic reusable module: target group, listener rule, task definition, ECS service; CI/CD manages task definition after initial creation (see ADR-012)
- environments/dev/main.tf updated — ECS cluster, ALB, and auth-workspace walking skeleton wired; image placeholder :skeleton pending first ECR push

Next milestone: Build auth-workspace Spring Boot container (returning 200 OK on /actuator/health), push :skeleton tag to ECR, write GitHub Actions deploy workflow. Service becomes reachable at the ALB DNS name.

## LAYER 3: POINTERS

- Architecture overview: docs/02-architecture/system-overview.md
- Tech choices and rationale: docs/02-architecture/technology-choices.md
- Cost strategy: docs/04-infrastructure/cost-strategy.md
- All ADRs: docs/06-decisions/
- Bootstrap Terraform: infrastructure/bootstrap/ (applied; local state only — see ADR-006)
- Shared Terraform: infrastructure/shared/ (applied; S3 remote state — see ADR-007 for OIDC)
- Dev environment Terraform: infrastructure/environments/dev/ (applied; S3 remote state — see ADR-008 for cross-module state sharing)
- VPC module: infrastructure/modules/vpc/ (public subnets for ECS — see ADR-009; 2 AZs — see ADR-010)
- Security groups module: infrastructure/modules/security-groups/ (ALB, ECS tasks, RDS; no Redis SG — Upstash is external)
- IAM ECS module: infrastructure/modules/iam-ecs/ (shared execution role + per-service task roles)
- CloudWatch module: infrastructure/modules/cloudwatch/ (per-service log groups, 7-day retention in dev)
- ECS cluster module: infrastructure/modules/ecs-cluster/ (Container Insights toggle — see ADR-011)
- ALB module: infrastructure/modules/alb/ (internet-facing ALB + HTTP listener; services own their target groups)
- ECS service module: infrastructure/modules/ecs-service/ (generic per-service module; ignore_changes on task_definition — see ADR-012)

## LAYER 4: ANTI-PATTERNS TO REJECT

- `any` in TypeScript without a comment justifying it
- Catch-and-ignore exceptions in any language
- Hardcoded secrets, hardcoded environment URLs
- New dependencies without ADR justification when alternatives exist
- Tests that hit real AWS in unit test runs
- Direct database access from controllers (must go through service layer)
- Field injection in Spring (@Autowired on fields)
- Default exports in TypeScript
- Mutable default arguments in Python
- Resources in Terraform without tags
