# ADR-015: RS256 over HS256 for JWT Signing

**Status:** Accepted
**Date:** 2026-05-01

---

## Context

The Auth & Workspace service issues JWTs that are validated by API Gateway on every request before forwarding to downstream services. Choosing a JWT signing algorithm determines which parties can verify tokens and — critically — which parties can *forge* them.

JWT signing algorithms fall into two categories:

- **Symmetric (HMAC-based, e.g., HS256):** a single secret key is used to both sign and verify tokens. Any party with the key can create valid tokens.
- **Asymmetric (RSA or EC-based, e.g., RS256, ES256):** a private key signs tokens; a public key verifies them. Only the party holding the private key can create valid tokens.

The central architectural question is: **should API Gateway be capable of forging tokens?**

---

## Decision

Use **RS256** (RSA-SHA256) for JWT signing.

- The Auth service holds the **private key** (in SSM Parameter Store). Only the Auth service can issue tokens.
- API Gateway, downstream services, and any other verifier hold only the **public key** (via the JWKS endpoint at `GET /.well-known/jwks.json`). They can verify tokens but cannot forge them.

The RSA key pair is 2048 bits minimum, targeting 4096 bits for new key generation.

---

## Alternatives Considered

**HS256 (HMAC-SHA256)**

HS256 uses a single shared secret. API Gateway would need a copy of this secret to validate tokens. With HS256:

- The Auth service holds the secret → can sign tokens. ✓
- API Gateway holds the secret → **can also sign tokens.** ✗

This is the critical problem. In a system where API Gateway holds the signing secret, a misconfigured API Gateway rule, a compromised Lambda authorizer, or a future developer who misunderstands the trust model could issue valid tokens for any user, including admins of any workspace. The signing secret becomes a single point of forgery.

Sharing the signing secret with API Gateway — an infrastructure component that is configured in Terraform and accessible to anyone with sufficient AWS IAM permissions — widens the attack surface beyond the Auth service. The Auth service is the only party that should be capable of creating identity claims.

HS256 is appropriate when the same system both issues and verifies tokens (for example, a monolith validating its own session cookies). It is not appropriate when the verifier is a separate infrastructure component that should not have forge capability.

**ES256 (ECDSA-SHA256)**

ES256 is an asymmetric algorithm using elliptic curve cryptography rather than RSA. It offers the same property as RS256 (public key verifies, private key signs) with smaller key sizes and faster operations. A 256-bit EC key is considered comparable to a 3072-bit RSA key.

ES256 is a legitimate alternative and is in some respects superior to RS256 for new systems. It is not chosen here because Spring Security's default JWT support and the AWS API Gateway JWT authorizer both have better-documented, more widely tested HS256 and RS256 integrations. ES256 is supported but less commonly encountered in tutorials and Stack Overflow answers — for a learning project, choosing the more common option reduces friction when debugging. This is a learning-context trade-off; for a production system, ES256 is worth considering.

---

## Key management

**Private key:** stored in AWS SSM Parameter Store as a SecureString at `/collabspace/<env>/auth/jwt-private-key`. The Auth service reads it at startup via the ECS task's IAM role (which has `ssm:GetParameter` permission on this path only). It is never logged, never returned in any API response, and never written to disk.

**Public key (JWKS):** the Auth service derives the public key from the private key at startup and serves it at `GET /.well-known/jwks.json` in JWK Set format. This endpoint is unauthenticated and publicly accessible — it contains only the public key.

**Key rotation:** rotating the key pair requires updating the SSM parameter with a new private key, redeploying the Auth service, and serving both the old and new public keys from the JWKS endpoint during the transition window (to allow in-flight tokens signed with the old key to drain within their 15-minute TTL). After one access token lifetime (~15 minutes), the old public key can be removed from the JWKS response.

---

## Consequences

**Positive:**
+ API Gateway can verify tokens without the ability to forge them. The Auth service is the sole token issuer.
+ The JWKS endpoint follows the OAuth2 / OpenID Connect standard — API Gateway, third-party tooling, and future services all consume it without custom integration code.
+ Key rotation does not require redeploying API Gateway or downstream services — they pick up the new public key on the next JWKS cache refresh.

**Negative:**
- RSA signing is slower than HMAC signing (~1ms vs ~0.01ms for HS256 at 2048 bits). For a service issuing tokens only at login and refresh, this is not a bottleneck — it would only matter if the Auth service were signing thousands of tokens per second.
- Key management is more complex than a shared secret: two values (private key, public key derivation), one SSM parameter, one public endpoint.
- Larger token size: RS256 signatures are 256 bytes base64-encoded; HS256 signatures are 32 bytes. Given that the fat JWT already carries membership claims, this is an incremental size increase that is not meaningful at the scale CollabSpace targets.

---

## Revisit when

- EC key support is well-established in the project's toolchain and the team is comfortable with it — ES256 becomes the better default for new key pairs.
- Token issuance volume reaches thousands per second, at which point RSA signing performance becomes measurable. (Not an expected concern at this scale.)
