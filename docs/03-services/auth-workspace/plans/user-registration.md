# Plan: user-registration

**Service:** auth-workspace  
**Tier:** Full  
**Status:** Draft

---

## 1. Slice statement

User can register.

---

## 2. User-visible behavior

- `POST /v1/auth/register` appears in Swagger UI with request/response schema.
- A valid registration returns `201 Created` with
  `{ accessToken, user: { id, email, name, createdAt } }`.
- A duplicate email returns `409 Conflict` with RFC 9457 Problem Details body.
- An invalid email format, or a password or name that violates length rules, returns
  `400 Bad Request` with an `errors` array identifying the failing fields.

---

## 3. API contract

**Path:** `POST /v1/auth/register`  
**Auth:** None (public route — no JWT required)

> **Note on `X-Internal-Token`:** the README specifies that every request must validate
> this header, but the check is not yet implemented in the service. This is a pre-existing
> gap — not introduced by this slice. It must land before any endpoint goes to production.
> It is out of scope here.

### Request body
```json
{
  "email": "Alice@Example.com",
  "password": "s3curepassword",
  "name": "Alice"
}
```

### Response — 201 Created
```json
{
  "accessToken": "<jwt>",
  "user": {
    "id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
    "email": "alice@example.com",
    "name": "Alice",
    "createdAt": "2026-06-02T10:00:00Z"
  }
}
```

- `id` is a UUID (matches the `users` table `UUID PRIMARY KEY`).
- `email` in the response is the **normalised (lowercased) value** — not the original
  input. A caller who registers with `Alice@Example.com` sees `alice@example.com` in
  the response.
- `createdAt` is ISO 8601 UTC (`2026-06-02T10:00:00Z`). Requires
  `spring.jackson.serialization.write-dates-as-timestamps=false` in
  `application.properties` — add as part of this PR.

> **Intentional deviation from `authentication.md`:** §Sign-up specifies that a refresh
> token is also issued and set as an HTTP-only cookie in the 201 response. This is
> intentionally deferred to the login PR. The response body matches; the cookie does not.

### Non-happy path status codes
| Scenario | Status |
|---|---|
| Validation failure (any field) | 400 |
| Missing request body | 400 |
| Duplicate email | 409 |

---

## 4. Data model changes

None. The `users` table was created in `V1__create_users.sql`:
- `id UUID PRIMARY KEY`
- `email VARCHAR(320) NOT NULL UNIQUE`
- `name VARCHAR(255) NOT NULL`
- `password_hash TEXT` (nullable — absent for future OAuth users)
- `created_at TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

No new migration required. For registration: `updated_at = created_at = Clock.instant()`.

---

## 5. Validation rules

Use `@NotBlank` (not `@NotNull`) on all string fields — `@NotBlank` rejects both null
and whitespace-only values. `@NotNull` alone would silently pass `"   "`.

| Field | Annotations | Error |
|---|---|---|
| `email` | `@NotBlank`, `@Email`, `@Size(max = 254)` | 400 + `errors` array |
| `password` | `@NotBlank`, `@Size(min = 6, max = 128)` | 400 + `errors` array |
| `name` | `@NotBlank`, `@Size(min = 1, max = 100)` | 400 + `errors` array |

**Email normalisation:** lowercasing is applied in the **service layer**, not the
controller. `RegisterUserCommand` carries the raw input; `AuthApplicationService`
normalises before the uniqueness check and before persistence. Normalisation in the
controller would be a transport concern leaking into business logic.

Unknown fields in the request body are ignored silently (Jackson default).

---

## 6. Edge cases

| Scenario | Status | Notes |
|---|---|---|
| Missing or blank `email`, `password`, or `name` | 400 | `@NotBlank` catches null and whitespace |
| Whitespace-only email (e.g. `"   "`) | 400 | `@NotBlank` rejects before `@Email` runs |
| Invalid email format | 400 | |
| `email` > 254 chars | 400 | Column is 320 — validation catches it first |
| `password` < 6 or > 128 chars | 400 | Max prevents bcrypt DoS (72-byte truncation) |
| `name` > 100 chars | 400 | Column is 255 — validation catches it first |
| Missing request body entirely | 400 | `HttpMessageNotReadableException` → 400 |
| Duplicate email | 409 | `EmailAlreadyTakenException` → `ConflictException` handler |
| Unknown fields in body | — | Ignored silently |

---

## 7. Authorization

Public route — no JWT required, no role checked.

`X-Internal-Token` validation is a pre-existing gap (see Section 3 note). Not in scope.

---

## 8. Observability

**Log lines emitted:**

| Event | Level | Fields |
|---|---|---|
| Registration success | INFO | `event=user_registered`, `userId`, `emailHash` (SHA-256), `ip`, `correlationId` |
| Duplicate email | WARN | `event=registration_rejected`, `reason=duplicate_email`, `emailHash`, `correlationId` |
| Validation failure | WARN | `event=registration_rejected`, `reason=validation_failed`, `fields` (names only — never values), `correlationId` |
| Unexpected exception | ERROR | `event=registration_error`, `correlationId` |

**IP address:** read from `X-Forwarded-For` header, first value. Fall back to
`HttpServletRequest.getRemoteAddr()` if the header is absent (e.g. local dev).

**Correlation ID:** propagated by `CorrelationIdFilter` into SLF4J MDC — automatically
appended to every log line. No manual injection needed in the service layer.

**Never log:** plaintext email, plaintext password, raw request body.

---

## 9. In-scope implementation work

### application.properties additions
```
spring.jackson.serialization.write-dates-as-timestamps=false
```

### New dependencies (pom.xml)
Add:
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`

Already present — do not add:
- `spring-boot-starter-security` (BCrypt lives here)
- Testcontainers

### Package reorganization
Feature sub-packages are applied consistently across all layers using two domain areas:
`auth` and `workspace`. One physical file move this PR:
`WellKnownController.java` → `adapter/in/rest/auth/`. Update `WellKnownControllerTest`
to reflect the new package path — it will not compile after the move otherwise.

**Full target structure (current PR scope marked ← this PR; future marked ← future):**

```
adapter/in/rest/
  auth/                              ← WellKnownController (moved), AuthController (this PR)
  workspace/                         ← WorkspaceController (future)
  CorrelationIdFilter.java           ← stays (cross-cutting)
  DbHealthIndicator.java             ← stays (cross-cutting)
  GlobalExceptionHandler.java        ← stays (cross-cutting)
  SecurityConfig.java                ← stays (cross-cutting)

adapter/out/
  persistence/
    auth/
      entity/UserEntity.java         ← this PR
      repository/UserJpaRepository.java ← this PR
      UserJpaAdapter.java            ← this PR
    workspace/                       ← future
  redis/
    auth/                            ← RedisTokenBlocklistAdapter (login PR)
  ssm/                               ← stays as-is

application/
  port/in/
    auth/                            ← RegisterUserUseCase, LoginUseCase, etc. (this PR +)
    workspace/                       ← CreateWorkspaceUseCase, etc. (future)
  port/out/
    auth/                            ← UserRepository, TokenBlocklistPort (this PR +)
    workspace/                       ← WorkspaceRepository (future)
  service/
    auth/                            ← AuthApplicationService (this PR)
    workspace/                       ← WorkspaceApplicationService (future)

domain/
  exception/                         ← stays flat (cross-cutting)
  model/
    auth/                            ← User (this PR); WorkspaceMembership moves here
    workspace/                       ← Workspace, etc. (future)
```

### ApplicationConfig (new @Configuration class)
Place at the application package root. Registers two beans:

- `Clock.systemUTC()` — injected into `AuthApplicationService` for `createdAt`/
  `updatedAt`. Allows tests to substitute a fixed clock without using `Instant.now()`
  directly.
- `PasswordEncoder` — `BCryptPasswordEncoder(12)`. BCrypt cost 12 (~300–400ms on modest
  hardware). Benchmark against actual ECS vCPU after first deployment; adjust if
  hashing takes >500ms or <200ms.

### Exception hierarchy
All classes in `domain/exception/`. Each exception declares
`public static final URI TYPE` and implements `getType()` from the abstract base —
the handler calls `ex.getType()` and never needs updating for new subclasses.

```
RuntimeException
  └── DomainException  (abstract — fallback → 422)
        ├── NotFoundException  (abstract → 404)  ← create now; UserNotFoundException lands in login PR
        └── ConflictException  (abstract → 409)
              └── EmailAlreadyTakenException      ← this PR
```

### GlobalExceptionHandler — handler order
All handlers inject `HttpServletRequest` and set `instance` to
`request.getRequestURI()`. `Content-Type: application/problem+json` (RFC 9457).

| Handler | Status |
|---|---|
| `MethodArgumentNotValidException` | 400 + `errors` array |
| `ConflictException` | 409 |
| `NotFoundException` | 404 |
| `DomainException` | 422 (fallback) |
| `Exception` | 500 (existing catch-all) |

### User domain model
`User` is a pure Java record in `domain/model/` — no Spring, no JPA annotations.
`passwordHash` is `Optional<String>` (absent for future OAuth users).

`UserEntity` uses a nullable `String` column for `password_hash`.
Mapping between `UserEntity` and `User` is **manual** — a package-private static helper
on `UserJpaAdapter`. No MapStruct; a mapping library is not justified for one entity
and would require an ADR.

### Implementation sequence (inside-out)
1. `User` domain record + `EmailAlreadyTakenException`
2. Exception hierarchy bases: `DomainException`, `NotFoundException`, `ConflictException`
3. `RegisterUserUseCase` (in-port) + `UserRepository` (out-port) + `RegisterUserCommand`
4. `AuthApplicationService` — normalise email to lowercase, check uniqueness, hash
   password, save user, issue access token. Annotate with `@Transactional` so a JWT
   signing failure after the DB insert rolls back the user row rather than leaving a
   half-registered user with no token. Use injected `Clock` — never `Instant.now()`
   directly.
5. `UserEntity` + `UserJpaRepository` + `UserJpaAdapter` (manual mapping)
6. `AuthController` + `RegisterRequest` (Bean Validation) + `RegisterResponse`
   — in `adapter/in/rest/auth/`
7. `ApplicationConfig` — `Clock` and `PasswordEncoder` beans
8. `GlobalExceptionHandler` — add 400 and 409 handlers
9. `application.properties` — add Jackson date config
10. OpenAPI annotations (`@Operation`, `@ApiResponse`, `@Schema`)
11. Tests: unit test for `AuthApplicationService` (fixed clock, mock `UserRepository`);
    integration test with Testcontainers for the full HTTP flow

---

## 10. Out of scope

- User login
- New database migrations (refresh_tokens V2 migration deferred to login PR)
- Redis integration (TokenBlocklistPort deferred)
- Refresh token cookie (deferred to login PR — see Section 3 note)
- `UserNotFoundException` → internal 401 mapping (deferred to login PR)
- `X-Internal-Token` validation filter (pre-existing gap — must land before production)
