# API Gateway Trust Model

This document explains how API Gateway validates JWTs, what it passes to downstream services, and — critically — why downstream services can trust those claims without re-validating the JWT themselves.

Understanding this is essential before implementing any service: a downstream service that does not understand the trust model will either re-validate unnecessarily (wasting latency) or miss validating something that API Gateway does not cover (a security bug).

---

## What API Gateway validates

API Gateway is configured with a **JWT Authorizer** that runs on every inbound request before routing. The JWT Authorizer:

1. Reads the `Authorization: Bearer <token>` header from the request.
2. Fetches the JWKS document from the Auth service (`GET /.well-known/jwks.json`) and caches it. This is done on a schedule — key rotation requires a deployment to flush the cache.
3. Validates the token signature against the RSA public key from the JWKS document.
4. Validates `exp` (token is not expired).
5. Validates `iss` — must match the configured issuer string (e.g., `https://auth.collabspace.io`).
6. Validates `aud` — must match the configured audience string (e.g., `collabspace-api`).
7. If all checks pass: extracts the JWT claims and makes them available as request context variables (`$context.authorizer.claims.sub`, `$context.authorizer.claims.userId`, etc.).
8. Maps context variables to HTTP headers on the forwarded request: `X-User-Id`, `X-User-Workspaces`.

API Gateway does **not** validate:

- Workspace membership or role authorization (that is per-service business logic).
- The JWT blocklist (`jti` check in Redis). That is per-service responsibility.
- Claims beyond `iss`, `aud`, `exp`, and signature.

If any validation fails, API Gateway returns `401 Unauthorized` and the request never reaches a downstream service.

---

## What downstream services receive

For every authenticated request that passes API Gateway validation, the downstream service receives:

| Header              | Value                           | Source                                    |
| ------------------- | ------------------------------- | ----------------------------------------- |
| `X-User-Id`         | The `userId` claim from the JWT | `$context.authorizer.claims.userId`       |
| `X-User-Workspaces` | JSON-encoded memberships array  | `$context.authorizer.claims.memberships`  |
| `X-Correlation-ID`  | Request ID for tracing          | `$context.requestId` (or client-supplied) |
| `X-Internal-Token`  | Shared secret (see below)       | API Gateway stage variable                |

Downstream services read `X-User-Id` and `X-User-Workspaces` to make authorization decisions. They do not parse or validate the JWT itself.

---

## Why downstream services can trust these headers

This is the central question. The headers arrive as plain HTTP headers — what stops an attacker from forging them?

### Primary control: network isolation

The downstream services (ECS tasks) are not publicly accessible. Their security group allows inbound traffic only from the ALB security group. The ALB accepts traffic only from API Gateway via a **VPC Link** — a private connection inside the VPC that does not traverse the public internet.

The network path for a legitimate request:

```
Internet → API Gateway (public endpoint)
              ↓ (validates JWT)
           VPC Link (private, inside VPC)
              ↓
           ALB (accepts only from VPC Link ENI)
              ↓
           ECS task (accepts only from ALB SG)
```

An attacker who bypasses API Gateway and attempts to call the ALB directly would need the ALB's DNS name (not published) and would be blocked by the ALB security group (which does not accept traffic from arbitrary internet IPs). An attacker inside the VPC would need to be running code inside the VPC — at which point the security group provides no protection, but the `X-Internal-Token` does (see below).

### Secondary control: internal token

API Gateway adds a static shared secret header (`X-Internal-Token`) to every forwarded request. This secret is stored in SSM and injected via an API Gateway stage variable at deploy time — it is never in source code.

Downstream services check for this header on every request. If it is absent or incorrect, the service returns `401` regardless of other headers.

This means an attacker inside the VPC who can reach the ALB directly (e.g., another ECS task) still cannot forge a valid request without knowing the internal token. The internal token is not a JWT — it does not expire — so it must be rotated manually if compromised. It is an operational secret, not a cryptographic primitive.

### What this means for service implementation

Downstream services:

- **Do** read `X-User-Id` and `X-User-Workspaces` and trust them.
- **Do** check the `X-Internal-Token` header and reject requests where it is missing or wrong.
- **Do** check the Redis JWT blocklist for `jti` on every request (the blocklist check is a service responsibility, not API Gateway's).
- **Do not** validate the JWT signature. The JWT is not forwarded to downstream services — only the extracted claims are.
- **Do not** call the Auth service to validate the user's identity per request.

This design makes the per-request hot path fast: no JWT validation, no Auth service call. The trade-off is that the security of the entire system depends on the network controls holding. Document and test those controls.

---

## Auth flow for a typical REST request

```
Browser                    API Gateway              Document Service
  |                             |                         |
  |-- GET /v1/documents/123 --->|                         |
  |   Authorization: Bearer <JWT>                         |
  |                             |                         |
  |                             |-- JWKS fetch (cached) --|
  |                             |-- validate signature    |
  |                             |-- validate exp, iss, aud|
  |                             |                         |
  |                             |-- GET /v1/documents/123 |
  |                             |   X-User-Id: abc123     |
  |                             |   X-User-Workspaces: [] |
  |                             |   X-Internal-Token: *** |
  |                             |                         |
  |                             |                         |-- check X-Internal-Token ✓
  |                             |                         |-- check Redis blocklist for jti
  |                             |                         |-- check workspace membership
  |                             |                         |-- fetch document
  |<--- 200 OK + document ------|<--- 200 OK + document --|
```

---

## Key rotation procedure

The Auth service signs JWTs with an RSA private key stored in SSM. The public key is served from `/.well-known/jwks.json`. API Gateway caches the JWKS response.

When rotating the signing key:

1. Generate a new RSA keypair.
2. Add the new public key to the JWKS response alongside the old one (the `keys` array can contain multiple keys, identified by `kid`). Update the Auth service JWKS endpoint and deploy.
3. Update SSM with the new private key. Restart the Auth service to pick it up.
4. Wait for all in-flight tokens signed with the old key to expire (up to 15 minutes).
5. Remove the old public key from the JWKS response. Deploy.
6. Force API Gateway to flush its JWKS cache (via a stage deployment or cache invalidation).

If step 5 is done before step 4, any in-flight token signed with the old key will fail validation during the gap. The 15-minute access token lifetime defines the safe rotation window.

---

## WebSocket traffic

WebSocket traffic is routed through the ALB directly, not through API Gateway. API Gateway's JWT Authorizer does not apply to WebSocket connections. WebSocket authentication is the Realtime Service's responsibility and is documented separately in:

- [ADR-020: WebSocket Authentication Flow](../06-decisions/adr-020-websocket-authentication.md)
- [authentication.md — WebSocket authentication section](authentication.md#websocket-authentication)
