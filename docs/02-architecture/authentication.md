# Authentication

Authentication in CollabSpace is handled exclusively by the Auth & Workspace service. No other service issues tokens, validates passwords, or manages sessions. This document describes the full authentication model: how credentials are stored, how tokens are designed and issued, how they are transported, how they expire, and how they are revoked. The end-to-end flows (sign-up, login, refresh, logout) are detailed with their exact database and Redis operations so the implementation cannot diverge from the design.

Authorization — what an authenticated user is permitted to do once identity is established — is a separate concern documented in [authorization.md](authorization.md).

---

## Design principles

The auth model is built around three goals that occasionally trade against each other: statelessness for the hot request path, security of credentials and tokens at rest, and revocability of issued tokens.

**Statelessness for the hot path.** API Gateway validates every incoming request using the JWT's signature and expiry — no network call to the Auth service is required per request. This means the token must carry everything a downstream service needs to make an authorization decision (user identity, workspace memberships, roles). The trade-off: if a user's role changes, the change does not take effect until their access token expires. At a 15-minute lifetime and for infrequent role changes, this delay is acceptable.

**Short-lived access, long-lived but revocable refresh.** The tension between "users should not re-authenticate constantly" and "tokens should be revocable" is resolved with two tokens: a short-lived access token (15 minutes) that API Gateway can validate without a network call, and a long-lived refresh token (7 days) that lives in a database and can be deleted on demand. This is the standard resolution of the stateless JWT revocation problem.

**Defense in depth.** Passwords are hashed with bcrypt before storage — a database dump does not expose credentials. Refresh tokens are hashed before storage — a database dump does not allow impersonation. Access tokens are signed asymmetrically (RS256) — API Gateway can verify them with the public key only, without holding a key capable of forging tokens. → [ADR-015](../06-decisions/adr-015-jwt-signing-algorithm.md)

---

## Password storage

Passwords are hashed with **bcrypt at cost factor 12** before storage. The hash is stored in the `users` table; the plaintext password is never persisted or logged.

Bcrypt is chosen over more recent alternatives (Argon2id, scrypt) because Spring Security's `BCryptPasswordEncoder` is the idiomatic choice in the Spring ecosystem, has been production-tested for decades, and has no known practical vulnerabilities. Argon2id is the current OWASP recommendation for new systems; it is noted as a revisit point if this service moves toward a production deployment that warrants it.

Cost factor 12 targets approximately 300–400ms of hashing time on a modest server. The exact timing depends on the ECS task's vCPU allocation and should be benchmarked against the actual task definition: if bcrypt takes longer than 500ms at cost 12, drop to 11; if it completes under 200ms, consider raising to 13. The goal is for hashing to be imperceptibly slow for a human logging in and meaningfully slow for an attacker attempting brute-force at scale.

---

## Token design

### Claims structure

Access tokens are JWTs with the following claims:

```json
{
  "sub": "user:01HZ...",
  "userId": "01HZ...",
  "memberships": [
    { "workspaceId": "ws:01HZ...", "role": "admin" },
    { "workspaceId": "ws:01HX...", "role": "member" }
  ],
  "iat": 1746000000,
  "exp": 1746000900,
  "jti": "tok:01HY..."
}
```

The `memberships` array is the defining characteristic of this design. Rather than issuing a minimal token (sub + exp only) and querying the database on each request to resolve roles, the token carries the user's full workspace membership set. Downstream services read the claims directly from the validated token without calling the Auth service.

This is called a "fat JWT." The trade-off: if a user's role changes (for example, promoted from member to admin), the change does not take effect until their current access token expires — up to 15 minutes later. For a collaboration product where role changes are infrequent and not time-critical, this is acceptable. If role changes needed to be instantaneous, the stateless model would not work and a centralized session store would be required instead.

`jti` (JWT ID) is a unique token identifier. Its purpose is revocation: when a user logs out, the `jti` is added to a Redis blocklist so that any remaining in-flight requests carrying that token are rejected. Without `jti`, a logout would not take effect until the token expired.

### Access token lifetime

Access tokens expire after **15 minutes**. This bounds the exposure window if a token is intercepted or leaked — the token is valid for at most 15 minutes after the user has logged out. Shorter lifetimes increase the frequency of silent refresh calls from the client; longer lifetimes widen the post-logout exposure window. 15 minutes is the standard balance point for this pattern.

### Signing

RS256 (RSA-SHA256, asymmetric). See [ADR-015](../06-decisions/adr-015-jwt-signing-algorithm.md).

---

## Refresh tokens

### What a refresh token is

A refresh token is a random 256-bit (32-byte) value generated by a cryptographically secure RNG (`SecureRandom` in Java). It has no internal structure — it is not a JWT. Its only purpose is to exchange for a new access token without the user re-entering their password.

### Storage

Refresh tokens are stored in a PostgreSQL `refresh_tokens` table. The token value itself is never stored — only a SHA-256 hash of the token is persisted. This means a database dump cannot be used to impersonate users by replaying tokens at the refresh endpoint.

SHA-256 is used here rather than bcrypt. The reason matters: bcrypt's deliberately slow hashing is designed to resist brute-force attacks on low-entropy inputs like passwords. A 32-byte cryptographically random token has ~256 bits of entropy and cannot be brute-forced regardless of hash speed — SHA-256 provides the same collision resistance at a fraction of the cost. Using bcrypt on a random token would add ~300ms to every refresh call for no security benefit.

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
```

`user_agent` and `ip_address` are recorded for audit trail purposes — a future "active sessions" UI can display this information. They are not used for token validation.

### Token rotation

A new refresh token is issued on every refresh — the old token is deleted atomically in the same database transaction. This limits the post-theft window: once the legitimate client refreshes using the stolen token, the old token is invalidated. If the legitimate client and the attacker both attempt to refresh simultaneously, one will succeed and one will fail; the failed party should trigger a forced logout and re-authentication.

### Transport

Refresh tokens are transported as **HTTP-only, Secure, SameSite=Strict cookies**.

- `HttpOnly`: the cookie cannot be read by JavaScript. XSS attacks cannot exfiltrate the refresh token even if they execute in the page context.
- `Secure`: the cookie is only transmitted over HTTPS. It is never sent over an unencrypted connection.
- `SameSite=Strict`: the cookie is only sent when the request originates from the same site as the cookie's domain.
- `Path=/auth`: the cookie is scoped to the `/auth` path prefix. It is not sent to document, realtime, or AI endpoints — only to the auth endpoints that need it.

**CORS implications.** The frontend (hosted on Vercel) and the API (on AWS) are on different origins. For the browser to send the refresh token cookie with cross-origin requests, two conditions must both be met:

1. Every auth-related fetch call from the frontend must set `credentials: 'include'`.
2. The Auth service response headers must include `Access-Control-Allow-Origin: <exact-frontend-origin>` and `Access-Control-Allow-Credentials: true`.

A wildcard `Access-Control-Allow-Origin: *` is browser-rejected when `credentials: 'include'` is set. The allowed origin must be the exact frontend URL (e.g., `https://app.collabspace.io`), not a pattern.

**SameSite=Strict and cross-origin requests.** SameSite=Strict prevents the cookie from being sent on cross-site requests, where "same-site" is determined by the registrable domain (the apex domain, not the full origin). If the frontend is on `app.collabspace.io` and the API is on `api.collabspace.io`, they share the `collabspace.io` apex domain — this is "same-site" under the SameSite definition, and Strict works. If the frontend and API are on entirely different apex domains (e.g., `app.vercel.app` and `api.amazonaws.com`), they are cross-site and Strict would block the cookie on the POST `/auth/refresh` request. In that case, SameSite=Lax is required. Evaluate the production domain setup before finalizing this flag; Strict is the default and the goal.

### Lifetime

Refresh tokens expire after **7 days**. Users who do not open the application for 7 days will be prompted to log in again. This mirrors standard consumer web application session lengths and reflects a balance between convenience and the risk of long-lived tokens in the database.

---

## Token revocation

Revocation on logout is a two-step operation, both of which must complete for full revocation:

1. **Delete the refresh token row** from `refresh_tokens`. This is immediate and hard — the token cannot be used to issue new access tokens after deletion. The Auth service does not need to track revoked refresh tokens in Redis because they are simply absent from the database.

2. **Add the access token's `jti` to the Redis blocklist.** The key is `blocklist:<jti>` with a TTL set to the token's remaining lifetime (`exp - now()`). Any downstream service receiving a request with this `jti` should check the blocklist and reject it.

The blocklist check is a per-service responsibility. API Gateway validates signature and expiry only — it does not check Redis. Each service is responsible for checking `blocklist:<jti>` on every authenticated request. The check is a single Redis GET; it adds one network round-trip to each request but does not require calling the Auth service.

**The fail-open gap.** Deleting the refresh token is immediate. Adding the `jti` to the Redis blocklist takes effect immediately for services that check it. However, a service that does not implement the blocklist check, or a service experiencing a Redis outage, will accept the access token for up to 15 minutes after logout. This is a known, accepted limitation of the stateless JWT model — documented in [ADR-002](../06-decisions/adr-002-auth-workspace-combined.md). The 15-minute window is short enough that the risk is acceptable; closing it would require a synchronous revocation check on every request, eliminating the statelessness benefit entirely.

---

## Auth flows

### Sign-up

1. Client sends `POST /v1/auth/register` with `{ email, password, name }`.
2. Service validates input at the controller boundary: email format, password minimum requirements, name non-empty.
3. Service checks that the email is not already registered. If it is, return `409 Conflict`.
4. Password is hashed with bcrypt (cost 12).
5. User row is inserted into the `users` table.
6. Access token and refresh token are issued (same as the login flow, steps 4–8 below). The user is logged in immediately after registration.
7. Response: `201 Created` with `{ accessToken, user }`. Refresh token is in the cookie.

### Login

1. Client sends `POST /v1/auth/login` with `{ email, password }`.
2. User is looked up by email. If not found, return `401 Unauthorized`. Do not reveal whether the email exists — the error message must be identical whether the email is absent or the password is wrong.
3. Password is verified with bcrypt comparison. If it does not match, return `401`.
4. A JWT access token is generated: claims `{ sub, userId, memberships, iat, exp, jti }`. Signed with the private RSA key (RS256). `jti` is a ULIDv2 or UUID.
5. A 32-byte cryptographically random refresh token is generated.
6. The refresh token is hashed with SHA-256. The hash is inserted into `refresh_tokens` with `expires_at = now() + 7 days`, along with the request's `User-Agent` and IP address.
7. The plaintext refresh token is set as the response cookie: `Set-Cookie: refresh_token=<value>; HttpOnly; Secure; SameSite=Strict; Path=/auth; Max-Age=604800`.
8. Response: `200 OK` with `{ accessToken, user }`.

### Token refresh

1. Client sends `POST /v1/auth/refresh`. No request body. The refresh token is read from the cookie.
2. If no cookie is present, return `401`.
3. The token value is hashed (SHA-256) and looked up in `refresh_tokens` by `token_hash`.
4. If no row is found, return `401`. If `expires_at < now()`, delete the row and return `401`.
5. Within a single database transaction: delete the old `refresh_tokens` row, generate a new refresh token, insert the new row.
6. Generate a new access token (new `jti`, new `iat`, new `exp`).
7. Set the new refresh token cookie (same flags as login).
8. Response: `200 OK` with `{ accessToken }`.

Token rotation is atomic — if the transaction fails, neither the deletion nor the insertion is committed. The client retries with the original cookie.

### Logout

1. Client sends `POST /v1/auth/logout`. The current access token is in the `Authorization: Bearer <token>` header. The refresh token is in the cookie.
2. Read the refresh token from the cookie. Hash it (SHA-256) and delete the matching row from `refresh_tokens`. If no row exists (already logged out, duplicate request), this is a no-op.
3. Read the `jti` claim from the access token. Write `SET blocklist:<jti> 1 EX <remaining_ttl>` to Redis, where `remaining_ttl = max(0, exp - now())`.
4. Clear the cookie: `Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/auth; Max-Age=0`.
5. Response: `200 OK`.

### Password reset

Password reset is **out of scope for v1**. It requires an email delivery integration (Amazon SES or a transactional email provider) and a time-limited, single-use token flow that is separate from the session token model. The user, token, and session schema are designed to be compatible with adding it — no redesign is required. It is deferred to v1.5 or later when email infrastructure is in scope.

---

## API Gateway integration

API Gateway is configured with a **JWT authorizer** that validates every request before forwarding it upstream. Configuration requires:

- **JWKS URL.** The Auth service exposes `GET /.well-known/jwks.json` returning the RSA public key in JWK Set format. API Gateway fetches this URL on a cache refresh schedule and uses the cached keys for validation. Key rotation requires rotating the SSM parameter, redeploying the Auth service with the new key, and adding the new public key to the JWKS response alongside the old one (to allow in-flight tokens signed with the old key to drain).
- **Issuer (`iss`).** The configured issuer string (e.g., `https://auth.collabspace.io`). API Gateway rejects tokens whose `iss` does not match exactly.
- **Audience (`aud`).** The configured audience string (e.g., `collabspace-api`). This prevents tokens issued for one API from being replayed against another.

API Gateway validates: **signature correctness, token expiry, issuer, audience.** It does not validate: workspace membership, role authorization, or token blocklist status. Per-service authorization (role and membership checks) and blocklist validation are the responsibility of each downstream service.

The JWKS endpoint must be publicly accessible — API Gateway calls it from within AWS infrastructure. It must return only the public key, never the private key. The private key lives in SSM Parameter Store at a path like `/collabspace/dev/auth/jwt-private-key` and is loaded by the Auth service at startup.

---

## Audit events

The following events are emitted as structured log lines and should always be present in the service's log output:

| Event | Log fields |
|---|---|
| Registration | `userId`, `email` (hashed), `ip`, `correlationId` |
| Login success | `userId`, `ip`, `userAgent`, `correlationId` |
| Login failure | `email` (hashed), `reason` (not_found \| bad_password), `ip`, `correlationId` |
| Token refresh | `userId`, `ip`, `correlationId` |
| Logout | `userId`, `jti`, `correlationId` |
| Blocklist check failure | `jti`, `userId`, `ip`, `correlationId` |

Email addresses are hashed in logs (SHA-256, non-reversible) to prevent plaintext PII in CloudWatch. A compliance-grade audit trail — stored durably, tamper-evidently, queryable by user and time range — is deferred to v1.5. → [roadmap.md](../roadmap.md)

---

## Out of scope

- **MFA.** The session model is compatible with MFA (a second factor can be enforced before the full-claims token is issued) but it is not a v1 requirement.
- **OAuth2 social login.** The explicit learning goal of this project is to implement JWT auth manually. → [ADR-002](../06-decisions/adr-002-auth-workspace-combined.md)
- **SAML / enterprise SSO.** Not in product scope.
- **API tokens.** Long-lived machine-to-machine tokens for third-party integrations are not a v1 concern.
- **Password reset.** Out of scope for v1; see Auth flows above.
