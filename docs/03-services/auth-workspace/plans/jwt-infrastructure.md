# Plan: jwt-infrastructure

**Feature branch:** `feat/auth/jwt-infrastructure`
**Tier:** Full
**Service:** auth-workspace

---

## 1. Slice statement

`JwtService` can issue RS256-signed access tokens and cryptographically random refresh tokens. Two public endpoints — `GET /.well-known/jwks.json` and `GET /.well-known/openid-configuration` — serve the RSA public key and OIDC discovery document respectively. No user data is created or modified in this slice. No business endpoints are implemented.

---

## 2. User-visible behavior

None directly. This slice unblocks two things:

1. **API Gateway JWT Authorizer** — Terraform can now wire the authorizer, which requires a live OIDC discovery endpoint at creation time. Once the authorizer is active, unauthenticated requests to protected routes will be rejected by API Gateway before they reach the service.
2. **PR 5 (register) and PR 6 (login)** — both can call `JwtService` without ordering conflicts.

---

## 3. API contract

### `GET /.well-known/jwks.json`

Public route. No `X-Internal-Token` required. Called by API Gateway on a cache refresh schedule to fetch the RSA public key.

**Response: `200 OK`**

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "<SHA-256 thumbprint of the key>",
      "n": "<base64url-encoded modulus>",
      "e": "<base64url-encoded public exponent>"
    }
  ]
}
```

`kid` is derived from the key itself (JWK thumbprint, RFC 7638) — not configured externally. The same key always produces the same `kid`. This is what links a token's `kid` header field back to the correct public key during validation.

The response contains the public key only. The private key is never included.

### `GET /.well-known/openid-configuration`

Public route. No `X-Internal-Token` required. Called by API Gateway once at JWT Authorizer creation time to discover the JWKS URI.

**Response: `200 OK`**

```json
{
  "issuer": "https://auth.dev.collabspace.io",
  "jwks_uri": "<value from /collabspace/dev/jwt/jwks-uri SSM parameter>",
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

`issuer` and `jwks_uri` are loaded from SSM at startup. `jwks_uri` changes on every `dev-down`/`dev-up` because the API Gateway endpoint URL is regenerated. It must not be hardcoded.

---

## 4. New dependency

**`com.nimbusds:nimbus-jose-jwt`**

Nimbus provides first-class support for:

- Parsing RSA private keys into a `RSAKey` object
- Deriving the public key from it (`.toPublicJWK()`)
- Computing the JWK thumbprint (`.computeThumbprint()`)
- Serialising a `JWKSet` to the exact JSON format expected by API Gateway

The alternative is building the JWK Set JSON manually — extracting the RSA modulus (`n`) and exponent (`e`), base64url-encoding them, and assembling the JSON. Nimbus handles this correctly and is the library Spring Security uses internally for the same purpose. No ADR is required for this dependency.

---

## 5. Key loading design

The RS256 private key is stored in SSM as a PKCS8 PEM string (format: `-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----`).

At startup, a `@Configuration` class in `adapter/out/ssm/` reads the PEM from SSM, parses it into a usable key object, and exposes two Spring beans:

- **`RSAKey` bean (nimbus type)** — contains both the private and public key. Used by `JwtService` for signing. The `kid` is set to the key's JWK SHA-256 thumbprint at construction time.
- The public key half (`.toPublicJWK()`) is used by `WellKnownController` for the JWKS response.

**Why a single `RSAKey` bean rather than separate private/public beans?** Nimbus's `RSAKey` holds both halves. Keeping them together ensures the `kid` is the same object in both the signing path and the JWKS path — there is no risk of them diverging.

The key loading class must not log the private key value or any key material, even at DEBUG.

---

## 6. `JwtService` design

Lives in **`application/service/`**. Has no infrastructure dependencies — it receives what it needs via constructor injection and performs pure computation.

**Constructor dependencies:**

- `RSAKey` — the nimbus key object (contains private key for signing)
- `JwtProperties` — issuer, audience, access token TTL (15 min), refresh token TTL (7 days)

**`JwtProperties`** is a record or `@ConfigurationProperties` class in `application/service/`. It holds values loaded from SSM at startup:

- `issuer` — from `/collabspace/dev/jwt/issuer`
- `audience` — from `/collabspace/dev/jwt/audience`
- `jwksUri` — from `/collabspace/dev/jwt/jwks-uri`

**Methods:**

`issueAccessToken(String userId, List<WorkspaceMembership> memberships) → String`

Returns a signed JWT string. Claims:

| Claim         | Value                                  |
| ------------- | -------------------------------------- |
| `sub`         | `"user:<userId>"`                      |
| `userId`      | the userId string                      |
| `memberships` | array of `{workspaceId, role}` objects |
| `iat`         | current epoch second                   |
| `exp`         | `iat + 900` (15 minutes)               |
| `jti`         | `UUID.randomUUID().toString()`         |
| `iss`         | from `JwtProperties.issuer`            |
| `aud`         | from `JwtProperties.audience`          |

The `kid` in the JWT header is set from the `RSAKey`'s thumbprint so API Gateway can match it to the JWKS response.

`issueRefreshToken() → RefreshTokenPair`

Generates a 32-byte cryptographically random token using `SecureRandom`. Returns a `RefreshTokenPair` record:

```
record RefreshTokenPair(String plaintext, String hash) {}
```

`plaintext` — base64url-encoded raw bytes. This is what goes in the cookie.
`hash` — SHA-256 hex digest of the raw bytes. This is what gets stored in the database.

`JwtService` does not touch the database. Persistence of the hash is the responsibility of the application service that calls this method (in PR 5 and PR 6).

**`WorkspaceMembership`** is a record in `domain/model/`:

```
record WorkspaceMembership(String workspaceId, String role) {}
```

---

## 7. Security routing

Both `/.well-known` routes are public and must be exempt from:

- The future `X-Internal-Token` filter (PR 7) — API Gateway calls these from its own infrastructure, not through the VPC Link
- JWT authentication — no `Authorization` header is required or expected

Spring Security's `SecurityFilterChain` must include `permitAll()` for `/.well-known/**`. This configuration already exists in some form — the JWKS route was listed as a public route in `services/auth-workspace/README.md` since PR 1.

---

## 8. Files to create

```
application/
  service/
    JwtService.java
    JwtProperties.java
    RefreshTokenPair.java
  domain/model/
    WorkspaceMembership.java

adapter/
  out/
    ssm/
      JwtKeyConfig.java          @Configuration — loads PEM from SSM, produces RSAKey bean
  in/
    rest/
      WellKnownController.java   GET /.well-known/jwks.json + /.well-known/openid-configuration
```

No new migration in this slice. `refresh_tokens` is added in PR 5 when it is first written to.

---

## 9. Edge cases

| Scenario                                       | Expected result                                                                                      |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| SSM parameter missing at startup               | Application context fails to load — `BeanCreationException`. Service does not start.                 |
| SSM value is not a valid PKCS8 PEM             | Application context fails to load with a descriptive error from the key parsing step.                |
| JWKS endpoint called while service is starting | Standard Spring Boot startup ordering — endpoint is not reachable until the context is fully loaded. |

---

## 10. Test plan

**Unit tests — `JwtService`**

Use a locally generated `KeyPair` (not SSM). Never hit a real AWS endpoint in tests.

- `issueAccessToken(userId, memberships)` → parse the returned JWT, assert claims: `userId`, `sub`, `memberships`, `iss`, `aud`, `jti` present, `exp - iat == 900`
- `issueRefreshToken` → assert plaintext decodes to 32 bytes; assert `SHA-256(plaintext) == hash`
- `issueRefreshToken` called twice → assert two different plaintext values (randomness check)

**Integration tests — well-known endpoints**

Use `@SpringBootTest` with a test `RSAKey` bean override (no real SSM).

- `GET /.well-known/jwks.json` → 200, response contains `keys` array, first key has `kty=RSA`, `alg=RS256`, `kid` present, `n` and `e` present, no `d` field (private key must not appear)
- `GET /.well-known/openid-configuration` → 200, `issuer` and `jwks_uri` match test properties, `id_token_signing_alg_values_supported` contains `RS256`

---

## 11. Out of scope

- `refresh_tokens` table migration — added in PR 5 alongside the first use
- Storing or validating refresh tokens — PR 5 and PR 6
- Token blocklist check — PR 7
- `X-Internal-Token` filter — PR 7
- Key rotation — not implemented; the `kid` thumbprint approach makes it mechanically possible, but rotation tooling is out of scope for v1
- Token validation (verifying an incoming JWT) — auth-workspace does not validate tokens it did not issue; that is API Gateway's responsibility per the trust model
- Terraform: wiring the `aws_apigatewayv2_authorizer` — a separate infra step after this PR merges, not part of the PR itself
