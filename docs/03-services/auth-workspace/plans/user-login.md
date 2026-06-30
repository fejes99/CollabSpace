# Plan: user-login

**Service:** auth-workspace  
**Tier:** Full  
**Status:** Draft

---

## 1. Slice statement

A registered user can log in with their email and password.

---

## 2. User-visible behavior

- `POST /v1/auth/login` with valid email and correct password returns `200 OK`
  with `{ accessToken, user: { id, email, name, createdAt } }` and a `Set-Cookie`
  header carrying an HttpOnly refresh token.
- Invalid credentials (wrong password, unknown email, or null password hash) return
  `401 Unauthorized` with RFC 9457 Problem Details — the response body is identical
  regardless of which condition failed.
- Missing or malformed `email` or `password` returns `400 Bad Request` with RFC 9457
  Problem Details.
- Every response carries an `X-Correlation-ID` header (echoed from the incoming
  header, or generated if absent).

---

## 3. API contract

**Path:** `POST /v1/auth/login`  
**Auth:** None (public route — no JWT required)

> **Note on `X-Internal-Token`:** per `api-gateway-trust.md`, every request must
> validate this header. The check is not yet implemented — it lands in PR 7. This is
> a pre-existing gap, not introduced by this slice.

### Request body

```json
{
  "email": "alice@example.com",
  "password": "s3curepassword"
}
```

### Response — 200 OK

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

`email` in the response is the stored (normalized, lowercased) value — returned
directly from the DB record, not from the submitted input.

Set-Cookie on success:

```
Set-Cookie: refresh_token=<plaintext>; HttpOnly; Secure; SameSite=Strict; Path=/auth; Max-Age=604800
```

The `Secure` flag is driven by the `app.cookie.secure` property (default `true`).
Override to `false` in `.env` for local dev — browsers reject `Secure` cookies over
plain `http://localhost`. Same pattern as the local JWT key config.

`Authorization` header is ignored if present. The trust model means the service
cannot re-validate a JWT on a public route without violating the architecture.
Session detection is a frontend concern.

**Transactional boundary:** the login method is annotated `@Transactional`. JWT
generation is in-memory and cannot be rolled back, but it is never returned to the
client if an exception is thrown. If the `refresh_tokens` INSERT fails, the
transaction rolls back and the caller receives a 500 with no tokens — no partial
state is visible. This matches the registration pattern from PR 5.

**Memberships in the JWT:** `memberships = []` for now — no workspace implementation
exists yet. When workspaces land, the login method will query the user's memberships
after verifying credentials and pass them to `JwtService.issueAccessToken()`. No
design change needed then — the method signature already accepts a
`List<WorkspaceMembership>`.

### Non-happy path status codes

| Scenario                         | Status |
| -------------------------------- | ------ |
| Invalid credentials (any reason) | 401    |
| Validation failure               | 400    |

---

## 4. Data model changes

### Migration: `V3__create_refresh_tokens.sql`

```sql
CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT         NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    user_agent  TEXT,
    ip_address  INET
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

`token_hash UNIQUE` creates an implicit index — no separate `CREATE INDEX` needed.  
`idx_refresh_tokens_user_id` prevents a full sequential scan when `ON DELETE CASCADE`
fires on `users`.  
`user_agent` and `ip_address` are audit trail only — not used in validation.

---

## 5. Validation rules

Login validation is intentionally looser than registration. No minimum on `password`
— a short password fails bcrypt comparison and returns `401`. Enforcing the minimum
here would let an attacker distinguish `400` (too short) from `401` (wrong password),
leaking account policy.

**Email normalization:** lowercase the submitted email before the DB lookup.
Registration stores emails lowercased; a login with `ALICE@EXAMPLE.COM` would fail
a case-sensitive query against the stored `alice@example.com` without this step.

| Field      | Annotations                               | Error                         |
| ---------- | ----------------------------------------- | ----------------------------- |
| `email`    | `@NotBlank`, `@Email`, `@Size(max = 254)` | 400 + `errors` array          |
| `password` | `@NotBlank`, `@Size(max = 128)`           | 400 (max prevents bcrypt DoS) |

Unknown fields are ignored silently (Jackson default).

---

## 6. Edge cases

| Scenario                              | Status | Notes                                                                                                         |
| ------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------- |
| Valid email, correct password         | 200    | Access token + refresh cookie issued                                                                          |
| Valid email, wrong password           | 401    | Identical body to "email not found"                                                                           |
| Email not found                       | 401    | Identical body to "wrong password"                                                                            |
| User found, `password_hash` is null   | 401    | Future OAuth user — never attempt bcrypt on null                                                              |
| `email` missing                       | 400    | `@NotBlank`                                                                                                   |
| `password` missing                    | 400    | `@NotBlank`                                                                                                   |
| `email` blank (`""` or whitespace)    | 400    | `@NotBlank`                                                                                                   |
| `password` blank (`""` or whitespace) | 400    | `@NotBlank`                                                                                                   |
| `email` malformed                     | 400    | `@Email`                                                                                                      |
| `password` > 128 chars                | 400    | `@Size(max = 128)`                                                                                            |
| Missing request body entirely         | 400    | `HttpMessageNotReadableException` — already handled by `GlobalExceptionHandler` (PR 1); no new handler needed |

---

## 7. Authorization

Public route — no JWT required, no role checked, no `@PreAuthorize`.

**Rate limiting:** API Gateway per-route throttling is the brute-force protection for
this endpoint — a companion Terraform change tracked separately from this service PR.
Account-level lockout (failed attempt counter in DB) is a documented future
improvement.

---

## 8. Observability

| Event            | Level | Fields                                                                                                                               |
| ---------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Login success    | INFO  | `event=user_logged_in`, `userId`, `ip`, `userAgent`, `correlationId`                                                                 |
| Login failure    | WARN  | `event=login_failed`, `emailHash` (SHA-256), `reason` (`not_found` \| `bad_password` \| `null_password_hash`), `ip`, `correlationId` |
| Unexpected error | ERROR | `event=login_error`, `reason` (e.g. `refresh_token_insert_failed`), `userId`, `correlationId`                                        |

**IP:** read from `X-Forwarded-For` first value, trimmed:

```java
String xForwardedFor = request.getHeader("X-Forwarded-For");
String ip = (xForwardedFor != null && !xForwardedFor.isBlank())
    ? xForwardedFor.split(",")[0].trim()
    : request.getRemoteAddr();
```

**User-Agent:** read from `request.getHeader("User-Agent")`. Use `null` if absent —
the `user_agent` column is nullable, and Logback omits null fields cleanly.

**Correlation ID:** propagated by `CorrelationIdFilter` into MDC — no manual
injection needed in the service layer.

**Never log:** plaintext email, plaintext password, raw request body.

**`jti` logging deferred to PR 7** — logging the `jti` alongside the blocklist
implementation keeps the full token lifecycle (issuance → blocklist → logout)
testable end-to-end in one PR.

**Carry-over:** retroactively add `ip` to `event=user_registered` in
`AuthApplicationService` — required by `authentication.md` §Observability table.

---

## 9. In-scope implementation work

### Housekeeping carry-overs

**`WorkspaceMembership` move:**  
`domain/model/WorkspaceMembership.java` → `domain/model/auth/WorkspaceMembership.java`.
Update all import references. No logic changes.

**Naming rename — drop `User` infix from auth port names:**

| Old name                   | New name               |
| -------------------------- | ---------------------- |
| `RegisterUserUseCase.java` | `RegisterUseCase.java` |
| `RegisterUserCommand.java` | `RegisterCommand.java` |

Files requiring import reference updates (no file rename):

- `AuthController.java`
- `AuthApplicationService.java`
- `JwtService.java` (for `WorkspaceMembership` path)
- `RegisterIntegrationTest.java`
- `RegisterTransactionalIT.java`
- `AuthApplicationServiceTest.java`

### New classes

| Layer       | Class                               | Notes                                     |
| ----------- | ----------------------------------- | ----------------------------------------- |
| Migration   | `V3__create_refresh_tokens.sql`     |                                           |
| Port (in)   | `LoginUseCase.java`                 |                                           |
| Port (in)   | `LoginCommand.java`                 | Fields: email (raw), password             |
| Port (in)   | `LoginResult.java`                  | Fields: accessToken string, user summary  |
| Port (out)  | `RefreshTokenRepository.java`       | Saves hashed token + metadata to Postgres |
| App service | `AuthApplicationService.login()`    | New method on existing class              |
| JPA entity  | `RefreshTokenEntity.java`           |                                           |
| Spring Data | `RefreshTokenJpaRepository.java`    |                                           |
| Adapter     | `RefreshTokenJpaAdapter.java`       | Implements `RefreshTokenRepository`       |
| DTO         | `LoginRequest.java`                 | Bean Validation annotations               |
| DTO         | `LoginResponse.java`                |                                           |
| Controller  | `AuthController` — add login method |                                           |

**`JwtService` notes — do not re-implement:**

- `issueAccessToken(userId, memberships)` — sets `sub = "user:" + userId`,
  `jti = UUID.randomUUID()`, signs RS256 ✓
- `issueRefreshToken()` — returns `RefreshTokenPair(plaintext, hash)` already ✓  
  `RefreshTokenRepository` only needs to store the hash.

**`app.cookie.secure` property:** add to `application.properties` with default `true`.
Override in `.env` with `APP_COOKIE_SECURE=false` for local dev.

### Implementation sequence

1. Write `V3__create_refresh_tokens.sql`. Apply on clean DB. Drop and re-apply —
   confirm idempotent.
2. Do housekeeping carry-overs: move `WorkspaceMembership`, rename `RegisterUser*`
   files, fix all import references. Compile — must be green before any new code.
3. Stub all new classes — signatures, types, `UnsupportedOperationException` bodies.
   Compile. Commit. Push draft PR.
4. Write integration test for happy path. Run — must fail.
5. Wire happy path: normalize email → look up user → check null hash → bcrypt compare
   → `jwtService.issueAccessToken(userId, List.of())` → `jwtService.issueRefreshToken()`
   → insert `refresh_tokens` row via `RefreshTokenRepository` → build Set-Cookie →
   return 200. Run test — must pass. Commit.
6. For each edge case row in Section 6: write the test first, then make it pass.
7. Add `ip` carry-over to `event=user_registered` in `AuthApplicationService`.
8. `./mvnw spring-javaformat:apply && ./mvnw validate`

---

## 10. Out of scope

- Does not wire Upstash Redis (token blocklist — deferred to infra/redis-upstash + PR 7)
- Does not implement the `X-Internal-Token` filter (deferred to PR 7)
- Does not re-implement registration
