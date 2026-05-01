# ADR-014: Service-to-Service Authentication via Short-Lived Signed JWT

**Status:** Accepted
**Date:** 2026-05-01

---

## Context

The AI Assistant fetches document content from the Document Service via a synchronous REST call (`GET /documents/:id`) when processing a Kafka indexing event. This is a service-to-service call — the Document Service is not receiving a request from an authenticated user but from another internal service. The Document Service needs to know that the caller is the legitimate AI Assistant, not a spoofed request or an external caller that has learned an internal endpoint URL.

This is the only internal synchronous call in the v1 system. All other cross-service communication is asynchronous (SNS/SQS, Kafka), where the broker provides delivery guarantees and the consumers trust the broker. → See [service-communication.md](../02-architecture/service-communication.md).

The authentication mechanism must satisfy three requirements:
1. The Document Service can verify the caller's identity without calling a third service.
2. A compromised token cannot be reused indefinitely.
3. The solution does not require operational infrastructure beyond what already exists (SSM Parameter Store, the services themselves).

---

## Decision

The AI Assistant authenticates to the Document Service using a **short-lived signed JWT** with a 5-minute TTL.

The JWT carries:
```json
{
  "iss": "ai-assistant",
  "aud": "document-service",
  "iat": 1746000000,
  "exp": 1746000300
}
```

The AI Assistant generates this token by signing a minimal claims set with its private RSA key (stored in SSM at startup). The Document Service validates the token using the AI Assistant's public key (also from SSM). No network call to a third party is required for validation — it is a local cryptographic operation.

Tokens are generated per-request and not cached. A 5-minute TTL means a captured token is usable for at most 5 minutes after capture before it expires.

---

## Alternatives Considered

**Shared secret / API key**

A secret string is stored by both services, sent in an `Authorization: Bearer <key>` or custom header, and compared on receipt. This is the simplest approach and requires the least code.

Rejected for two reasons. First, a shared secret does not carry caller identity — the Document Service knows a secret was presented, but has no way to distinguish the AI Assistant from any other service that has obtained the secret. Second, rotating the secret requires coordinated redeployment of both services; an unrotated compromised secret is valid indefinitely. At this scale, a shared secret is acceptable operationally, but the JWT approach is not significantly more complex and is meaningfully more secure.

**mTLS (mutual TLS)**

Both services present client certificates; each validates the other's certificate chain. mTLS provides cryptographic identity verification at the transport layer — stronger than application-layer tokens because it cannot be bypassed by application bugs.

Rejected because the operational overhead is disproportionate at this scale. mTLS requires a certificate authority (or a managed service like AWS ACM Private CA), certificate provisioning for each service identity, certificate rotation on a schedule, and potentially a service mesh to manage it. For a single internal call between two services, the complexity cost is not justified. If the system grows to dozens of services with strict zero-trust networking requirements, mTLS is the correct solution.

**VPC-only, no application-layer authentication**

Deploy both services in the same VPC and configure security groups to allow traffic only from within the VPC. Any caller that reaches the Document Service's internal port is assumed to be a trusted internal service — no token, no certificate, no validation.

Rejected because it provides no audit trail and no caller identity. If the Document Service logs an unusual number of fetch requests, there is no way to attribute them to a specific service. More importantly, VPC-only isolation relies on network configuration correctness; a misconfigured security group or a compromised service in the VPC can reach the endpoint without any application-layer check. Defense in depth argues for application-layer authentication even when network isolation is in place.

**AWS IAM-based authentication (SigV4)**

Services authenticate to each other by signing requests with their ECS task IAM role credentials. The receiving service validates the SigV4 signature using the AWS SDK. AWS handles credential rotation automatically.

A valid option, particularly in an AWS-native architecture. Rejected because it tightly couples the authentication mechanism to AWS IAM — porting the pattern to a different environment or testing it locally requires either a real AWS session or LocalStack configuration. The JWT approach is portable and testable without AWS credentials.

---

## Consequences

**Positive:**
+ Caller identity is explicit in the token (`iss: "ai-assistant"`). The Document Service can log which internal caller made each request.
+ A captured token expires in 5 minutes and cannot be reused beyond that window.
+ No third-party call is required for validation — the Document Service verifies the signature locally with the public key.
+ The pattern is portable: it works identically in local dev, with LocalStack, and in AWS.
+ If additional internal calls are added in future services, the same pattern applies without new infrastructure.

**Negative:**
- Both services must read signing keys from SSM at startup, adding to the SSM parameter surface area.
- Clock skew between services can cause spurious validation failures if clocks drift significantly (>30 seconds). ECS tasks use Amazon Time Sync Service, which keeps drift well under this threshold; this is a known risk to monitor but not a practical concern in this environment.
- No centralised token issuance or revocation — each service manages its own key. At two services, this is fine; at ten services with independent key pairs, key management becomes a consideration.

---

## Revisit when

- More than two or three internal synchronous calls exist and key pair management becomes cumbersome. At that point, a centralised internal token issuer (or a service mesh with mTLS) is worth evaluating.
- The system adopts a zero-trust networking posture that requires all lateral traffic to be cryptographically authenticated at the transport layer — mTLS or a service mesh becomes appropriate.
- AWS Private CA costs come down or are otherwise absorbed, making mTLS operationally tractable.
