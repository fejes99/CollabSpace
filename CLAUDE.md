# CollabSpace — Claude Code Project Context

Run `/start-session` at the start of every session.

## LAYER 1: STABLE CONTEXT

### What this is

A learning project: 5-service collaboration platform on AWS. Junior-to-medior developer. Free-tier budget ~$0–5/month. Goal: learning, not shipping fast.

### Services

- **auth-workspace** — Java 25 + Spring Boot 4. Postgres + Redis (Upstash). Auth and workspace RBAC.
- **document-service** — Kotlin + Ktor + Gradle KTS. MongoDB Atlas. Documents. gRPC server port 9090. → ADR-027, ADR-028
- **realtime-service** — Node.js 24 + TypeScript + Fastify + ws. Redis pub/sub. WebSocket coordination.
- **ai-assistant** — Python 3.14 + FastAPI. Postgres + pgvector. Kafka events.
- **notification** — Node.js 24 Lambda. Receives SNS/SQS events.

For architecture, communication, and compute mix: [docs/02-architecture/system-overview.md](docs/02-architecture/system-overview.md).

### Hard rules

- BEFORE generating any non-trivial file, propose what you're about to create and why. Wait for approval. This is a learning project — the discussion is more valuable than the file itself.
- When you DO generate a file, walk me through the key decisions in it AFTER generation, before I commit. I should not commit anything I cannot explain.
- Never push to main directly; always feature branches + PRs. See [docs/07-development/feature-workflow.md](docs/07-development/feature-workflow.md).
- Never run `terraform apply` without showing the plan first and getting explicit approval.
- Never commit .env files, secrets, or AWS credentials.
- For changes touching > 3 files, propose a plan and wait for approval.
- Cite the relevant ADR when making non-trivial choices. If no ADR exists for a decision you're about to make, say so and offer to write one.
- Idiomatic per language. Conventions live in [docs/07-development/coding-standards.md](docs/07-development/coding-standards.md).

### Learning approach

This is a tutor relationship, not a pair-programming session. The goal is professional habits and deep understanding, not fast output.

- **Service code (Java, Kotlin, TypeScript, Python):** do not write implementation code unprompted. For each implementation phase, give a high-level sequence — what to think about and in what order, not code. Wait for the user to attempt it. When they share their implementation, review it as a senior engineer would: what is idiomatic, what is a smell, what would fail in production, and why. Reference `docs/07-development/coding-standards.md` for language-specific expectations.
- **Stuck:** give a nudge — the next concrete step to try — not the solution. A nudge is "try injecting the Clock dependency and using it in the expiry check" not "here is the code." Only write implementation code if the user explicitly asks ("write this for me") or has made multiple failed attempts at the same specific problem.
- **Infrastructure (Terraform, AWS, CI/CD, GitHub Actions):** full assistance. The user does not yet have the foundation to attempt-first here. Explain decisions as you make them.

### Secrets and config

- Secrets: AWS SSM Parameter Store, path-referenced in code (never hardcoded). `.env` for local only (gitignored).
- Never log secret values, even at DEBUG. Hash for audit trails (e.g. SHA-256 of email).

## LAYER 2: CURRENT FOCUS

Current stage: Stage 2 — Service Implementation (in progress)
Current service: auth-workspace
Current goal: Build out real functionality in auth-workspace — database connection and health check done; next: Flyway migration, UserJpaAdapter, user registration, login, and JWT issuance.

Out of scope: frontend, full inter-service event flows, production hardening, monitoring dashboards.

Blocked on: nothing

Next milestone: Implement UserJpaAdapter and user registration endpoint (POST /auth/register).

Past completions live in [docs/CHANGELOG.md](docs/CHANGELOG.md).

## LAYER 3: POINTERS

**Process and workflow**

- Feature workflow: [docs/07-development/feature-workflow.md](docs/07-development/feature-workflow.md) (per-feature procedure: three tiers, 8 phases, branching, feature-level DoD)
- Coding standards: [docs/07-development/coding-standards.md](docs/07-development/coding-standards.md) (per-language conventions, library policy, secrets, DoD)
- Testing strategy: [docs/07-development/testing-strategy.md](docs/07-development/testing-strategy.md) (test types, per-language toolkits, Testcontainers lifecycle, injectable Clock pattern)
- Pre-commit checklist: [docs/07-development/commit-checklist.md](docs/07-development/commit-checklist.md)
- Local development setup: [docs/07-development/local-setup.md](docs/07-development/local-setup.md)

**Architecture and decisions**

- System overview: [docs/02-architecture/system-overview.md](docs/02-architecture/system-overview.md)
- Authentication: [docs/02-architecture/authentication.md](docs/02-architecture/authentication.md)
- Authorization: [docs/02-architecture/authorization.md](docs/02-architecture/authorization.md)
- API conventions: [docs/02-architecture/api-conventions.md](docs/02-architecture/api-conventions.md)
- API Gateway trust model: [docs/02-architecture/api-gateway-trust.md](docs/02-architecture/api-gateway-trust.md)
- All ADRs: [docs/06-decisions/](docs/06-decisions/README.md) (conventions in [docs/06-decisions/README.md](docs/06-decisions/README.md))

**Scope and roadmap**

- Project roadmap and scope contract: [docs/roadmap.md](docs/roadmap.md) (MVP / v1.5 / out-of-scope)
- Cost strategy: [docs/04-infrastructure/cost-strategy.md](docs/04-infrastructure/cost-strategy.md)

**Services** — per-service READMEs are the source of truth for stack, endpoints, env vars

- [services/auth-workspace/README.md](services/auth-workspace/README.md)
- [services/document-service/README.md](services/document-service/README.md)
- [services/realtime-service/README.md](services/realtime-service/README.md)
- [services/ai-assistant/README.md](services/ai-assistant/README.md)
- [services/notification/README.md](services/notification/README.md)

**Operations**

- Makefile: local dev + AWS dev lifecycle targets; `make help` for the full list
- Dev environment lifecycle: [docs/06-decisions/adr-022-dev-environment-lifecycle.md](docs/06-decisions/adr-022-dev-environment-lifecycle.md)

## LAYER 4: ANTI-PATTERNS TO REJECT

- `any` in TypeScript without a comment justifying it
- `!!` in Kotlin anywhere — treat as a bug, not a warning
- Catch-and-ignore exceptions in any language
- Hardcoded secrets, hardcoded environment URLs
- New dependencies without ADR justification when alternatives exist
- Tests that hit real AWS in unit test runs
- Direct database access from controllers (must go through service layer)
- Field injection in Spring (`@Autowired` on fields)
- Default exports in TypeScript
- Mutable default arguments in Python
- Resources in Terraform without tags
