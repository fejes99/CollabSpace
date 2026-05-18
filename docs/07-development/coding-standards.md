# Coding Standards

Per-language conventions, library policy, and the "what's idiomatic here" reference for every service in CollabSpace.

This document is loaded on demand — `CLAUDE.md` points to it but does not duplicate it. When working on a service, read the section for that service's language before generating non-trivial code.

---

## General principles

- **Idiomatic per language.** Pythonic Python, Spring conventions for Java, modern TypeScript with strict mode for Node and Fastify. Don't transplant idioms across languages — each ecosystem has reasons for its own patterns.
- **Behavior over structure.** Tests test behavior, not internal layout. Functions are named for what they *do*, not what they're called from.
- **Boundaries are explicit.** Input validation lives at the request handler. Errors are caught at the top of each request. Logs include correlation IDs.

---

## Java (Spring Boot)

For `auth-workspace`.

- Constructor injection only — no `@Autowired` on fields.
- Records for DTOs. Skip Lombok (records cover most cases).
- `Optional<T>` over null returns.
- `@Transactional` on service methods that span multiple repository calls.
- Bean Validation (`jakarta.validation`) at the controller boundary.
- No direct database access from controllers — go through the service layer.
- Constructor-inject `java.time.Clock` for any time-sensitive code. Test fixtures supply a `Clock.fixed(...)` so JWT expiry, token TTLs, and blocklist windows are deterministic. See [testing-strategy.md](testing-strategy.md) §7.
- Format with Spring Java Format (`spring-javaformat-maven-plugin` in `pom.xml` enforces it in CI; IntelliJ plugin installed from GitHub releases applies it on save). Run `./mvnw spring-javaformat:apply` to fix all files at once.

---

## TypeScript (Fastify)

For `document-service`, `realtime-service`, `notification`.

- `strict: true` in `tsconfig.json`. No `any` without an inline comment justifying it.
- Named exports only — no default exports.
- JSON Schema on Fastify routes for HTTP boundary validation. Use `zod` for business-logic validation and infer types from schemas (do not write parallel types by hand).
- `pino` for logging; never `console.log`.
- `async`/`await` throughout; never raw `.then()/.catch()` chains.
- Package manager: `pnpm` (see [ADR-018](../06-decisions/adr-018-pnpm-package-manager.md)). Do not run `npm install` in Node services.
- Inject a `Clock` interface in time-sensitive code (see [testing-strategy.md](testing-strategy.md) §7). Never call `Date.now()` directly inside a function body.

---

## Python (FastAPI)

For `ai-assistant`.

- Type hints on every public function signature (parameters and return type).
- Pydantic models for request and response. No raw dicts crossing API boundaries.
- `async def` for I/O-bound code.
- `structlog` for logging; never `print()` or `logging.info()`.
- `ruff` for lint, `black` for format.
- **Do not use `response_model=` on route decorators when the return type annotation is already present.** It duplicates information; FastAPI infers the schema from the annotation.
- **Each Python service needs a `pyrightconfig.json` with `"reportUntypedFunctionDecorator": "none"`.** Pyright cannot infer through FastAPI's route-decorator generics; this suppresses the false positive without weakening `ruff` or `black`.
- Constructor-inject a clock dependency (see [testing-strategy.md](testing-strategy.md) §7).
- No mutable default arguments.

---

## Terraform

- `snake_case` for resource names.
- All resources tagged: `Environment`, `Service` (where applicable), `ManagedBy = "terraform"`.
- `for_each` over `count`.
- Module per concept, not per service. A module's reusability comes from its scope being a single concept (e.g. "an ECS service") not a single consumer (e.g. "the auth-workspace service").
- No hardcoded account IDs, region strings, or ARNs — use variables or data sources.

---

## Library policy

- **New dependency requires ADR justification** when the standard library or an existing dependency could do the job. The default answer is "no new dependency"; the ADR is how you say yes anyway.
- **TypeScript DI containers**: prefer manual DI or `awilix` over `inversify` or `tsyringe` at this scale. Heavy DI containers fight rather than help small Fastify apps.
- **TypeScript ORMs**: Mongoose for MongoDB. No second ORM. Avoid generic abstractions over both Mongo and Postgres in the same service.
- **Java**: avoid Lombok (records cover most cases). Avoid heavy auth libraries — implementing JWT manually is an explicit learning goal of this project.
- **JavaScript / Node**: avoid `moment.js` (use `date-fns` or native `Intl`).

---

## Secrets and config

- **Local dev**: `.env` files (in `.gitignore`), loaded by the service at startup.
- **Deployed**: AWS SSM Parameter Store, *not* Secrets Manager (cost — see relevant ADR).
- Reference pattern in code: read the SSM path at startup; never hardcode the value.
- Never log secret values, even at DEBUG level. Hash them if you need to trace through (e.g. SHA-256 of an email for audit logs).

---

## Definition of Done (per service feature)

Apply this when a feature is considered finished. For the per-feature workflow that *gets* you to done, see [feature-workflow.md](feature-workflow.md).

- Unit tests + at least one integration test (see [testing-strategy.md](testing-strategy.md)).
- OpenAPI spec updated, auto-generated where the framework supports it.
- Service README updated to describe the new behavior.
- Deployed via CI/CD to AWS dev — actually running, not just committed.
- Observable: structured logs with correlation ID on all new code paths.
- ADR written if a non-trivial decision was made.
