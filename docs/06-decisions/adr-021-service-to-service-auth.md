# ADR-021: Service-to-Service Authentication

**Status:** Accepted
**Date:** 2026-05-04

---

## Context

ADR-014 identified service-to-service authentication as an open design question. This ADR closes it.

The immediate case is the AI Assistant calling `GET /documents/:id` on the Document Service to fetch document content for indexing. This is an internal call — it is never triggered by a user action directly — and the Document Service must be able to distinguish between requests from the AI Assistant and requests from an authenticated user routed through API Gateway.

The concern is not hypothetical. If the Document Service's internal endpoints are accessible to any service in the VPC without application-level auth, then:

- A misconfigured or compromised ECS task can read any document by ID, bypassing workspace access controls.
- There is no audit trail of which service accessed which document.
- Future internal endpoints (e.g., bulk export for the Notification service) have no auth model to follow.

Both services run inside the same VPC. Network-level isolation (security groups) restricts which ports are reachable from outside the VPC, but within the VPC and the shared ECS tasks security group, all services can reach each other on all ports.

---

## Decision

Use **internal service JWTs** signed with RS256. The AI Assistant mints a short-lived JWT with service-identifying claims and presents it in the `Authorization: Bearer` header on each internal HTTP call. The Document Service validates this JWT using the same RS256 public key infrastructure already established for user JWTs (ADR-015).

Internal service JWTs use a distinct claim set that cannot be confused with user JWTs:

```json
{
  "sub": "service:ai-assistant",
  "iss": "collabspace-internal",
  "aud": "document-service",
  "iat": 1746000000,
  "exp": 1746003600
}
```

The Document Service validates:

- Signature (RS256, same public key as user JWTs).
- `iss` must be `collabspace-internal`.
- `aud` must be `document-service`.
- `exp` must be in the future.

When `iss` is `collabspace-internal`, the Document Service applies service-level authorization (AI Assistant can read any document in any workspace, not bounded by user membership). When `iss` is the user auth issuer (`https://auth.collabspace.io`), standard user authorization applies.

Internal JWTs are issued with a **1-hour lifetime** and cached by the calling service. The AI Assistant regenerates the token when `exp - now() < 5 minutes` to avoid gaps.

---

## Rationale

### Why application-level auth at all

Network isolation (the ECS tasks security group) limits access to services within the VPC. This is a necessary control but insufficient on its own:

- All ECS tasks share the ECS tasks security group. A compromised Document Service container could call the AI Assistant or any other service.
- Future services added to the VPC automatically join the security group. Without application-level auth, every new service immediately has full access to every other service.
- Audit: with no application-level auth, CloudWatch logs cannot attribute an internal request to a specific calling service.

Application-level auth adds defense in depth and creates an explicit trust boundary that scales as services are added.

### Why internal service JWTs

The RS256 infrastructure is already in place (ADR-015). The Auth service holds the RSA private key in SSM; every service that validates JWTs already fetches the public key (or receives it at startup). Adding internal JWT validation reuses this infrastructure rather than introducing a new auth mechanism.

The `iss` claim is the discriminator. A single validation function in the Document Service can route to service-level authorization or user-level authorization based on `iss`. This keeps the auth middleware simple and avoids a parallel validation code path.

Internal JWTs are minted by the calling service itself, not by the Auth service. The calling service reads the RSA private key from SSM at startup and uses it to sign. This avoids a dependency on the Auth service for internal calls — if the Auth service is down, inter-service calls still work.

This requires the RSA private key to be available to services that make internal calls (AI Assistant, and potentially others in the future). The key is stored in SSM at `/collabspace/{env}/auth/jwt-private-key` and read at startup. Services that only validate (not sign) only need the public key.

### Why 1-hour token lifetime

Internal services are not end users. They do not log out; the call pattern is continuous. A 15-minute lifetime (the user token lifetime) would require re-minting tokens frequently for a long-running consumer. An hour reduces SSM calls and token generation overhead.

An hour is short enough that a revocation event (a compromised service being replaced) takes effect within the hour without manual intervention. This is acceptable for internal service communication where revocation is an emergency measure, not a routine operation.

### Why the AI Assistant signs its own token

Alternatives considered:

1. **AI Assistant calls Auth service to mint a token.** Creates a circular dependency: if Auth is down, AI cannot call Document Service. This is a tight coupling that defeats the purpose of independent services.
2. **Document Service trusts all requests from the ECS tasks SG.** Network-only auth; rejected (see above).
3. **AI Assistant uses a pre-minted long-lived token stored in SSM.** A static token is a credential that can be exfiltrated and replayed indefinitely. Rejected.

Self-minting with a shared private key is the right balance: the private key is a secret, but it is already a secret (it exists in SSM for the Auth service). Giving the AI Assistant read access to it via IAM is a controlled expansion of the trust boundary. The minted token has a short lifetime, so exposure is bounded.

---

## Rejected alternatives

**Network isolation only**

Relying solely on the VPC security group means any service in the `collabspace-ecs-tasks` security group can call any internal endpoint without presenting credentials. This provides no service-level attribution and no defense against a compromised container calling internal endpoints it should not reach.

Rejected: insufficient for a project that is explicitly learning production security patterns.

**AWS IAM task roles with SigV4 signing**

Each service's ECS task role could be used to sign requests with SigV4. The Document Service would validate the SigV4 signature and confirm the caller's IAM role identity.

This is the AWS-native pattern and has real advantages: no shared secret, the IAM principal is a first-class AWS identity, and IAM policies can grant fine-grained permissions.

Rejected because:

- SigV4 signing requires the AWS SDK in every calling service. The AI Assistant already uses boto3, so this is not a new dependency for it — but it adds friction for future services in different runtimes.
- Validating SigV4 in the Document Service (a Node.js Fastify service) requires either `@aws-sdk/signature-v4` or a custom verification step. This is more complex than JWT validation, which the service already does.
- It ties the auth mechanism to AWS. In local development with LocalStack, IAM role validation is emulated but not identical. JWT validation is identical in all environments.

SigV4 is the better choice for services that are deeply AWS-native. For a learning project that aims for environment consistency, JWTs are more portable and simpler to reason about.

**mTLS (mutual TLS)**

Each service presents a client certificate; the server validates the certificate against a trusted CA. Provides strong cryptographic identity without shared secrets.

Rejected because:

- Certificate management (issuing, rotating, distributing) requires a PKI. At this project's scale, operating a PKI is significant overhead.
- AWS Certificate Manager does not manage mTLS certificates for service meshes without App Mesh.
- App Mesh is out of scope (cost and operational complexity for a learning project).

mTLS is the right answer for service meshes at scale. For five services with clear inter-service call patterns, internal JWTs are proportionate.

---

## Security boundaries

| Caller                | Called                     | Auth mechanism                                     | Authorization scope                  |
| --------------------- | -------------------------- | -------------------------------------------------- | ------------------------------------ |
| Browser client        | API Gateway → any service  | User JWT (validated by API Gateway)                | Workspace membership from JWT claims |
| AI Assistant          | Document Service           | Internal service JWT (`iss: collabspace-internal`) | Read any document (service-level)    |
| (future) Notification | Auth Service               | Internal service JWT                               | Read user contact info               |
| Any service           | Any service (health check) | None                                               | Public health endpoint only          |

Internal endpoints that require service-level auth must reject requests carrying user JWTs (wrong `iss`). User endpoints must reject requests carrying internal service JWTs (wrong `iss`). This separation prevents a service JWT from being used to impersonate a user, and vice versa.

---

## Consequences

**Positive:**

- Reuses existing RS256 infrastructure — no new key management system.
- `iss` discriminator keeps user and service auth in the same middleware stack.
- Short-lived tokens (1 hour) bound the exposure window for a compromised token.
- Audit trail: structured logs include `sub: service:ai-assistant` on every internal request.
- Works identically in local dev and AWS (no LocalStack emulation of IAM needed for this flow).

**Negative:**

- The RSA private key is now read by the AI Assistant as well as the Auth service. The IAM policy for the AI task role must be expanded to read this SSM parameter. This is a deliberate and controlled expansion; document it in the IAM module.
- Self-minting adds code to the AI Assistant (a JWT library and a token refresh loop). This is a small but non-zero complexity addition.
- Revocation of a compromised service requires key rotation (because the service signs its own tokens). Key rotation is a documented procedure (in `docs/02-architecture/authentication.md`) but is more disruptive than revoking a single token. This is an accepted trade-off for the eliminated Auth service dependency.

---

## Revisit when

- More than 3 services make internal calls to each other. At that point, the shared private key model becomes harder to reason about (which services have the key? what if one is compromised?). Evaluate moving to per-service asymmetric keypairs or AWS IAM task role SigV4.
- App Mesh or a service mesh is adopted. mTLS via the mesh sidecar becomes the natural replacement.
- The Auth service is made highly available (multi-AZ, health-checked). At that point, depending on Auth to mint internal tokens is no longer a single-point-of-failure concern.
