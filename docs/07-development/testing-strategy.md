# Testing Strategy

This document defines how to test CollabSpace services. It is the source of truth for which tests to write, which to skip, where they live, and how they run.

It exists because most engineering effort wasted on tests is not "we didn't write enough" — it is "we wrote the wrong ones." A test suite full of mocks of your own code, fixture-heavy setup, and snapshots of internal state is worse than no tests: it slows the feedback loop, breaks on every refactor, and gives false confidence. The senior habit being trained here is to ask, before every test: *what real behavior does this protect?*

**Cross-references**

- Per-feature workflow: [feature-workflow.md](feature-workflow.md) — when each test type is written.
- Per-commit hygiene: [commit-checklist.md](commit-checklist.md).
- MVP scope: [../roadmap.md](../roadmap.md).

---

## 1. Three principles

**Test behavior, not structure.** A test that breaks when you rename a private method is a bad test. A test that breaks when the public contract changes is a good test. Test through the public seam — the HTTP endpoint, the queue consumer, the CLI — not through internal classes.

**Do not mock your own code.** Mock external systems you do not own (email senders, third-party APIs). Never mock your own repositories, services, or DB clients — those mocks lie. They make tests pass while production breaks because the mock matches your *belief* about how the code behaves, not what it actually does.

**One concept per test.** If a test contains two `// arrange / // act` blocks, it is two tests. Each test answers one question. Each failure points at one cause.

These three rules eliminate ~80% of common test-suite pathologies. Internalize them before reading the rest of this document.

---

## 2. What each test type means

Five types are used in this project. Definitions are precise on purpose — "integration test" means different things in different shops, and ambiguity here costs hours later.

| Type | Definition |
|---|---|
| **Unit** | Tests a pure function or class in isolation. No I/O. No framework boot. No database. Runs in <10ms. |
| **Integration** | Tests a feature slice through the framework: HTTP request → route handler → service → repository → real DB, all in-process. Uses Testcontainers for the DB. Runs in <1s after the container is warm. |
| **Contract** | Asserts the response shape matches the OpenAPI schema. Can be embedded inside an integration test. |
| **Smoke** | Runs against the **deployed** service in AWS dev. Verifies the deployment is live and responds. Already implemented in CI per service. |
| **E2E** | A multi-service flow tested end-to-end. **Deferred** — not written until ≥2 services have stable contracts and interact. |

What "integration test" does NOT mean in this doc:

- Not a test over HTTP from outside the process (that's a smoke test).
- Not a function call to a service bean with mocked repositories (that's a unit test of the service).
- Not a test of two services together (that's E2E, deferred).

---

## 3. The pyramid

| Layer | What | Where | How many | How fast |
|---|---|---|---|---|
| Unit | Pure logic: validators, mappers, calculators | Inside the service | Many | <10ms each |
| Integration | One feature slice end-to-end | Against Testcontainers | One per slice | <1s each (post-warmup) |
| Contract | OpenAPI shape match | Embedded in integration tests | One per endpoint | <500ms |
| Smoke | curl against deployed AWS | Post-deploy CI step | One per service | seconds |
| E2E | Multi-service flow | Deferred | 0 for now | n/a |

Ratios are not enforced. The shape that emerges from following the rules in §1 is naturally pyramid-shaped: many small unit tests, fewer integration tests, almost no E2E.

---

## 4. Per-language toolkits

### Java (Spring Boot — auth-workspace)

| Concern | Library |
|---|---|
| Test runner | JUnit 5 |
| Framework integration | Spring Boot Test (`@SpringBootTest`, `@AutoConfigureMockMvc`) |
| Real Postgres | Testcontainers `postgresql` module |
| Real Redis | Testcontainers `GenericContainer` with `redis:7` image |
| Assertions | AssertJ |
| Mocks (external only) | Mockito |
| Coverage report | JaCoCo (visible, not enforced) |

Add to `pom.xml`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

### TypeScript (Fastify — document, realtime, notification)

| Concern | Library |
|---|---|
| Test runner | vitest |
| In-process HTTP | Fastify's built-in `app.inject()` — preferred over supertest |
| Real Postgres / Mongo / Redis | `testcontainers` (npm) |
| Assertions | vitest built-in (`expect`) |
| Coverage report | c8 (already configured) |

`app.inject()` is the Fastify idiom — it routes a synthetic request through the full framework pipeline without opening a network socket. Same fidelity as supertest, less overhead, no extra dependency.

### Python (FastAPI — ai-assistant)

| Concern | Library |
|---|---|
| Test runner | pytest + pytest-asyncio |
| In-process HTTP | `httpx.AsyncClient` with `ASGITransport` |
| Real Postgres | testcontainers-python |
| Real Redis | testcontainers-python `GenericContainer` |
| Coverage report | pytest-cov (visible, not enforced) |

Add to `requirements-dev.txt`:

```
pytest
pytest-asyncio
httpx
testcontainers[postgres]
```

---

## 5. Test database lifecycle

**Strategy: per-suite container + per-test transaction rollback.**

The container starts once per test class/file (~5 seconds). Each test runs inside a transaction that is rolled back on teardown. Tests are isolated without paying the container-startup cost on each one. Post-warmup, each test runs in well under a second.

For Mongo, transactions across collections require a replica set. Pragmatic alternative: drop and recreate the test database between tests. Acceptable cost for the document-service test volume expected in MVP.

### Java sketch (Postgres + Spring + Testcontainers)

```java
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@Transactional
class RegisterUserIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mvc;

    @Test
    void registersNewUserAndReturnsJwt() throws Exception {
        mvc.perform(post("/v1/auth/register")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"email":"alice@example.com","password":"P4ssword!","name":"Alice"}
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.user.email").value("alice@example.com"));
    }
}
```

`@Container static` ⇒ one container shared per test class. `@Transactional` on the class ⇒ each test runs in a transaction that Spring rolls back automatically at test teardown.

### TypeScript sketch (Postgres + Fastify + testcontainers)

```typescript
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { buildApp } from '../src/app.js';
import type { FastifyInstance } from 'fastify';

let pg: StartedPostgreSqlContainer;
let app: FastifyInstance;

beforeAll(async () => {
  pg = await new PostgreSqlContainer('postgres:16').start();
  app = await buildApp({ databaseUrl: pg.getConnectionUri() });
});

afterAll(async () => {
  await app.close();
  await pg.stop();
});

beforeEach(async () => {
  await app.db.query('BEGIN');
});

afterEach(async () => {
  await app.db.query('ROLLBACK');
});

describe('POST /v1/documents', () => {
  it('returns 201 with a document id for valid input', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/v1/documents',
      payload: { title: 'Hello', body: 'World' },
    });

    expect(res.statusCode).toBe(201);
    expect(res.json()).toMatchObject({ title: 'Hello' });
  });
});
```

For Mongo: replace `BEGIN/ROLLBACK` with `await app.mongo.db.dropDatabase()` in `beforeEach`.

### Python sketch (Postgres + FastAPI + testcontainers-python)

```python
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport
from testcontainers.postgres import PostgresContainer
from app.main import create_app


@pytest.fixture(scope="session")
def postgres():
    with PostgresContainer("postgres:16") as pg:
        yield pg


@pytest_asyncio.fixture
async def client(postgres, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", postgres.get_connection_url())
    app = create_app()

    async with app.router.lifespan_context(app):
        # Begin transaction here, roll back in teardown.
        # Pattern depends on the chosen async ORM (SQLAlchemy + asyncpg).
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as ac:
            yield ac


@pytest.mark.asyncio
async def test_health_returns_200(client):
    res = await client.get("/health")
    assert res.status_code == 200
```

---

## 6. External services: real, stub, or mock?

Decision table for everything that lives outside the service-under-test.

| External | Strategy | Notes |
|---|---|---|
| PostgreSQL | Real (Testcontainer) | Per-suite container + per-test rollback |
| MongoDB | Real (Testcontainer) | Per-suite container + dropDatabase per test |
| Redis (Upstash in prod) | Real (Testcontainer) | Upstash is Redis-compatible; `FLUSHALL` between tests |
| SSM Parameter Store | Stub (env vars in tests) | Tests inject config via env vars; same interface as prod |
| JWKS endpoint | Real (own service) | The Auth service exposes its own JWKS; tests call it via the integration harness |
| SNS / SQS | LocalStack (later) | Deferred until notification SNS/SQS wiring starts |
| SES (email, v1.5) | Mock | True external; mock the sender interface |
| API Gateway | Stub | Tests bypass API Gateway and call the service directly with a forged-but-valid JWT signed by the test fixture key |
| Time / Clock | Stub (fixed Clock) | Injectable `Clock` interface — see §7 |

**Forging valid JWTs in tests.** The Auth service generates its RSA key pair at startup (from SSM in prod, from a test fixture in tests). Integration tests of other services receive a fixed test public key, sign tokens with the matching private key, and submit them as `Authorization: Bearer …`. This tests the full JWT-validation path without standing up the Auth service.

---

## 7. Time, clocks, and randomness

Time-sensitive code is the single biggest source of flaky tests in young codebases. The fix is an **injectable Clock from feature #1.** No `LocalDateTime.now()`, no `Date.now()`, no `datetime.now()` anywhere in business code — those are flakiness generators waiting to fire.

### Java

```java
@Configuration
class ClockConfig {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}

// Production code:
@Service
class TokenIssuer {
    private final Clock clock;
    TokenIssuer(Clock clock) { this.clock = clock; }

    Instant accessTokenExpiry() {
        return clock.instant().plus(Duration.ofMinutes(15));
    }
}

// Test override:
@TestConfiguration
class TestClockConfig {
    @Bean @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC);
    }
}
```

### TypeScript

```typescript
export interface Clock {
  now(): Date;
  nowMs(): number;
}

export const systemClock: Clock = {
  now: () => new Date(),
  nowMs: () => Date.now(),
};

// In tests:
export function fakeClock(startISO: string): Clock & { advance(ms: number): void } {
  let current = Date.parse(startISO);
  return {
    now: () => new Date(current),
    nowMs: () => current,
    advance: (ms) => { current += ms; },
  };
}
```

Inject via your DI container or constructor parameters. Never reach for `Date.now()` inside a function body — that function becomes untestable.

### Python

```python
from datetime import datetime, timezone, timedelta
from typing import Protocol


class Clock(Protocol):
    def now(self) -> datetime: ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now(timezone.utc)


class FixedClock:
    def __init__(self, instant: datetime):
        self._instant = instant

    def now(self) -> datetime:
        return self._instant

    def advance(self, **kwargs) -> None:
        self._instant += timedelta(**kwargs)
```

### Randomness and IDs

ULIDs/UUIDs in tests cause assertion noise. Two options:

1. **Don't assert on them.** Use `expect.any(String)` or `containsExactlyInAnyOrder`.
2. **Inject an ID generator.** Same pattern as `Clock`. The test substitutes a deterministic generator.

Default to option 1. Reach for option 2 only when an ID is part of an externally-visible contract (e.g., a workspace slug derived from a ULID).

---

## 8. Test data and fixtures

**Use builder functions, not fixture files.** Builders are version-controlled, refactor-safe, IDE-friendly, and produce fully-valid defaults that tests can override.

### Java

```java
public class UserBuilder {
    private String email = "user-" + UUID.randomUUID() + "@example.com";
    private String passwordHash = "$2a$12$validlookingbcrypthashstring1234567890";
    private String name = "Test User";

    public UserBuilder email(String email) { this.email = email; return this; }
    public UserBuilder name(String name) { this.name = name; return this; }
    public User build() { return new User(email, passwordHash, name); }
}

// In a test:
User user = new UserBuilder().email("alice@example.com").build();
```

### TypeScript

```typescript
import { ulid } from 'ulid';

export function aUser(overrides: Partial<User> = {}): User {
  return {
    id: ulid(),
    email: `user-${ulid()}@example.com`,
    name: 'Test User',
    ...overrides,
  };
}
```

### Python

```python
import ulid

def a_user(**overrides) -> User:
    defaults = {
        "id": str(ulid.new()),
        "email": f"user-{ulid.new()}@example.com",
        "name": "Test User",
    }
    return User(**{**defaults, **overrides})
```

**Rules of thumb:**

- Defaults are realistic and fully valid — a test never has to set up a "valid" baseline before exercising the thing it cares about.
- Randomize what should not matter (IDs, timestamps, padding fields). Fix what the test is asserting on.
- One builder per entity. Builders compose — `aWorkspace().withMember(aUser().role("admin")).build()`.
- Builders live in `test/support/` (Java), `tests/builders/` (TS), `tests/factories/` (Python).

---

## 9. Test naming conventions

Behavior in the name. Long names are fine — they are documentation.

### Java (JUnit 5)

```java
@Test
void shouldReturn409WhenEmailAlreadyRegistered() { ... }

@Test
void rejectsRegistrationWithEmptyEmail() { ... }

@Test
void issuesJwtThatExpiresInExactly15Minutes() { ... }
```

**Pattern:** `<verb><expected behavior><condition>`. No `test_` prefix. No `testRegister1()`.

### TypeScript (vitest)

```typescript
describe('POST /v1/auth/register', () => {
  it('returns 201 with a JWT for valid input', async () => { ... });
  it('returns 409 when the email is already registered', async () => { ... });
  it('returns 400 when the email format is invalid', async () => { ... });
  it('issues a refresh token cookie with HttpOnly and Secure flags', async () => { ... });
});
```

**Pattern:** `describe` names the unit (often the endpoint). `it` names the behavior — reads as a sentence: "POST /v1/auth/register returns 201 with a JWT for valid input."

### Python (pytest)

```python
async def test_register_returns_201_with_jwt_for_valid_input(client): ...
async def test_register_returns_409_when_email_exists(client): ...
async def test_register_rejects_invalid_email_format(client): ...
```

**Pattern:** `test_<unit>_<behavior>`. Snake-case full sentences.

---

## 10. What NOT to test

This list saves more time than any positive testing advice. Most junior test suites are 70% framework boilerplate and 30% behavior. Senior test suites are 90% behavior.

**Do not test:**

- Getters, setters, `toString`, `equals`, `hashCode`. Records and `@Data` already produce correct ones; testing them tests the language, not your code.
- Third-party library behavior — JSON serialization, Spring's `@Autowired`, Fastify's request parsing, FastAPI's Pydantic conversion. You do not own these.
- Framework wiring. If `@Service` is autowired, do not write a test asserting it is autowired. If it isn't, the app won't start — which is its own test.
- Type definitions. The type checker handles those.
- Code paths the type system already proves impossible (e.g., a `Result<T>` branch where the variant is excluded by exhaustive matching).
- Configuration values for their own sake. Test the behavior that depends on the configuration, not the value of a constant.
- Logging statements. Logs are a side-effect, not a contract. The audit-event tests in §4 of the feature plan are an exception — those *are* the contract for compliance.

If you find yourself writing a test that boils down to "did I call the method I just defined?" — delete it.

---

## 11. Flakiness rules

**A test that fails non-deterministically is broken.** Period. Three options:

1. Fix the root cause in the next commit.
2. Delete the test until you can fix it.
3. Quarantine it explicitly in a separate file with an issue link.

What is forbidden: leaving a flaky test in the regular suite, with or without `@Disabled` / `it.skip` / `@pytest.mark.skip`. A skip-as-workaround teaches you to ignore failures, and that is how real bugs get shipped.

Common flakiness sources to look for first:

- Shared state between tests (most often: a static field, a singleton, a non-rolled-back DB row).
- Real network calls (HTTP, DNS, NTP, an external service the test forgot to stub).
- Time-based assertions using the system clock — see §7.
- Async ordering (a callback fires before the assertion runs).
- Test ordering dependence — if tests pass alone but fail together, isolation is broken.

---

## 12. What we are NOT doing yet

Documented here so you do not invent justification later. Each row has a trigger condition that turns deferral into action.

| Concern | Status | Trigger |
|---|---|---|
| Browser E2E (Playwright) | Deferred | Frontend exists and has a stable UI |
| Multi-service backend E2E | Deferred | ≥2 services have stable contracts and interact in dev |
| LocalStack for AWS services | Deferred | Notification SNS/SQS wiring starts |
| Contract testing (Pact) | Deferred | Multi-service E2E proves insufficient |
| Property-based testing | Deferred | Validators get genuinely complex (e.g., custom date parsing, intricate state machines) |
| Mutation testing | Deferred | Coverage becomes a concern (it is not now) |
| Load and performance testing | Deferred | One endpoint measured >100ms median in production |
| Chaos testing | Out of scope | See [../roadmap.md](../roadmap.md) |
| Coverage thresholds enforced in CI | Deferred | Possibly never; behavior coverage matters, not line coverage |
| Terraform tests (terratest, checkov) | Deferred | Infrastructure changes start producing avoidable regressions |

---

## Putting it together

For your first feature (auth-workspace user registration), the test set looks like:

1. **Unit tests** for the password-hasher, the JWT issuer, the email validator. Each pure, each <10ms.
2. **One integration test** for the happy path: `POST /v1/auth/register` returns 201 with a JWT. Real Postgres via Testcontainers, real bcrypt hashing, real JWT signing with a test key pair. Transaction rolled back at teardown.
3. **Edge-case integration tests:** duplicate email returns 409, invalid email returns 400, weak password returns 400, missing fields returns 400.
4. **Contract assertion** embedded in (2) and (3): response shape matches the OpenAPI schema.
5. **Smoke test in CI** (already in place): the deployed service returns `200 OK` from `/actuator/health` on AWS.

That is the full test surface for one feature. Roughly: 5 unit + 5 integration + 1 smoke = 11 tests. Each one tests something real. No mocks of own code. No fixture files. Time and IDs injected.

This shape repeats for every feature.
