# ADR-028: gRPC for AI Assistant → Document Service Internal Communication

**Status:** Accepted
**Date:** 2026-06-01

---

## Context

ADR-021 defines the authentication model for AI Assistant → Document Service internal calls using internal service JWTs signed with RS256. At the time ADR-021 was written, the assumed transport was REST HTTP — the ADR references "HTTP call" and "Authorization: Bearer header." This ADR supersedes the transport assumption; ADR-021's JWT model is unchanged.

The concrete use case: the AI Assistant consumes `document.updated` events from Kafka. For each event it needs the full document content to (re-)generate embeddings and store them in pgvector. The Document Service owns the document content; the AI Assistant must fetch it from the Document Service without reading MongoDB directly (that would violate service ownership boundaries). Additionally, on startup, the AI Assistant performs a bulk re-index by listing all documents in each workspace — a second access pattern that a single REST endpoint cannot serve efficiently.

---

## Decision

The AI Assistant → Document Service internal communication uses **gRPC**.

The Document Service runs a gRPC server on port **9090** alongside its Ktor HTTP server on port **8080**. Port 9090 is VPC-internal only and never routed through API Gateway. The AI Assistant is the gRPC client.

### Proto contract

The contract lives at `/proto/document.proto` in the monorepo root. Both services reference it as their source of truth.

```protobuf
syntax = "proto3";
package collabspace.document.v1;

service DocumentService {
  rpc GetDocument(GetDocumentRequest) returns (DocumentResponse);
  rpc ListDocuments(ListDocumentsRequest) returns (stream DocumentResponse);
}

message GetDocumentRequest {
  string document_id = 1;
}

message ListDocumentsRequest {
  string workspace_id = 1;
  optional string modified_after = 2; // ISO 8601 — incremental re-index after downtime
}

message DocumentResponse {
  string document_id = 1;
  string workspace_id = 2;
  string title = 3;
  string content = 4;           // plain text or markdown body; no binary blobs
  string updated_at = 5;        // ISO 8601
  optional string attachment_url = 6; // reference URL only; binary not included
}
```

`GetDocument` covers the event-driven case: one Kafka event → one document fetch. `ListDocuments` covers bulk re-index: stream all documents in a workspace on AI Assistant startup, with an optional `modified_after` filter for incremental catch-up after downtime. Server streaming is used for `ListDocuments` so MongoDB cursor results are streamed without accumulating the full result set in memory.

### Error mapping

| Condition | gRPC status |
|---|---|
| Document not found | `Status.NOT_FOUND` |
| Workspace not found | `Status.NOT_FOUND` |
| Internal / unexpected error | `Status.INTERNAL` |

No bare exceptions may escape the gRPC handler. All errors are caught and mapped to a gRPC `Status` with a descriptive message. A `ServerInterceptor` on the Kotlin side catches any unhandled `Throwable` and maps it to `Status.INTERNAL`.

### Authentication

Service JWT auth per ADR-021 applies without modification to the JWT itself. The delivery mechanism differs: instead of an HTTP `Authorization` header, the AI Assistant attaches the JWT as gRPC metadata under the key `authorization` with value `Bearer <token>`. This is the conventional gRPC auth pattern and is handled symmetrically:

- **Kotlin server**: a `ServerInterceptor` extracts `authorization` from `io.grpc.Metadata` and validates the JWT using the same logic defined in ADR-021 — `iss: collabspace-internal`, `aud: document-service`, RS256 signature, `exp` in the future.
- **Python client**: a gRPC `CallCredentials` or client interceptor attaches the JWT metadata to every outgoing call. The AI Assistant mints and refreshes its token using the same logic described in ADR-021 (1-hour lifetime, refresh when `exp - now() < 5 minutes`).

The gRPC server rejects calls with missing or invalid `authorization` metadata with `Status.UNAUTHENTICATED`.

### Service discovery

The AI Assistant resolves the Document Service's gRPC address via environment variables:

| Variable | Local / Docker Compose | AWS ECS |
|---|---|---|
| `DOCUMENT_SERVICE_GRPC_HOST` | `localhost` | `document-service.collabspace.local` |
| `DOCUMENT_SERVICE_GRPC_PORT` | `9090` | `9090` |

In AWS, the Document Service's ECS service registers tasks in **AWS Cloud Map** via a `service_registries` block in the Terraform ECS service resource. ECS auto-registers and deregisters tasks on start and stop. The private DNS name `document-service.collabspace.local` resolves within the VPC via a Route 53 private hosted zone created automatically by Cloud Map. DNS TTL is 10 seconds — acceptable for the AI Assistant's channel refresh cycle.

Cloud Map is provisioned when the gRPC server is first deployed to AWS dev. It is not required for local development or unit testing — the env var abstraction handles both environments.

### Toolchain

**Kotlin side** — Gradle `com.google.protobuf` plugin compiles `/proto/document.proto` and generates coroutine-native stubs via `protoc-gen-grpc-kotlin`. The Gradle build references the monorepo `/proto` directory using a relative path. Generated sources are placed in `build/generated/` and never committed.

**Python side** — `grpcio-tools` generates stubs via:
```bash
python -m grpc_tools.protoc \
  -I ../../proto \
  --python_out=generated/ \
  --grpc_python_out=generated/ \
  ../../proto/document.proto
```
Generated stubs are committed to `services/ai-assistant/generated/`. When the proto contract changes, regenerate and commit. This avoids requiring `protoc` in the Python CI environment.

**CI behaviour**: the Kotlin build detects proto changes automatically (Gradle incremental compilation). The Python stubs are committed, so CI passes as long as they are regenerated and committed alongside any proto change. A CI lint rule can enforce this (diff check between committed stubs and freshly generated ones).

---

## Rationale

### Why gRPC instead of REST for this call

The AI Assistant → Document Service call is internal, synchronous, and service-to-service with no user interaction. Several characteristics make gRPC a better fit than REST for this specific call:

**Typed contract.** The proto file is the source of truth for both client and server. A REST API's contract lives in documentation or an OpenAPI spec, which can drift from the implementation. A generated gRPC stub is derived from the same proto file both sides compile — schema drift is a build error, not a runtime surprise.

**Coroutine integration.** `grpc-kotlin` generates `suspend` functions natively. The Python async stubs (`grpcio` async API) are equally coroutine-native. No `runBlocking`, no thread pools, no callback wrappers on either side.

**Server streaming.** The bulk re-index use case fetches all documents in a workspace. REST would require pagination across multiple round trips, each with serialization overhead. gRPC server streaming sends results as they emerge from the MongoDB cursor — no accumulation, no extra round trips, and the client processes each document as it arrives.

**Learning value.** gRPC is a widely-used production pattern. Implementing server (Kotlin) and client (Python) in the same project with a shared proto contract teaches: protobuf schema design, code generation, interceptors for auth, streaming, and the operational difference between REST and binary protocol design. This cannot be learned by adding another REST endpoint.

### Why a separate port (9090)

API Gateway does not support gRPC. The gRPC server must be VPC-internal only. Separating it on port 9090 makes the boundary explicit: 8080 is the public REST surface (API Gateway → ECS), 9090 is the internal surface (AI Assistant ECS → Document Service ECS). Mixing both on 8080 would require Ktor to handle gRPC routing, complicate the API Gateway configuration, and risk accidentally exposing the gRPC endpoint.

### Why Cloud Map for service discovery

The gRPC call is VPC-internal — it bypasses API Gateway and must resolve the Document Service's address within the VPC. Options considered:

| Option | Verdict |
|---|---|
| Hard-coded task IP | Rejected — ECS task IPs change on every restart |
| Internal ALB (L7) | Rejected — ALB terminates HTTP/2; gRPC requires end-to-end HTTP/2 from client to server. An ALB can be configured for gRPC health checks but adds latency and cost (~$15/month) |
| Internal NLB (L4 TCP passthrough) | Valid but costs ~$15/month — outside the $0–5 budget |
| ECS Service Connect | Valid, zero-cost, AWS-native. More configuration than Cloud Map at two-service scale. Revisit at v1.5 if service count grows |
| AWS Cloud Map | Free tier covers this entirely. ECS has native integration — one `service_registries` block in Terraform. DNS-based, runtime-agnostic, works with any gRPC client |

Cloud Map is the right choice for this project: free, simple, and ECS-native.

### Why committed Python stubs

Generating Python gRPC stubs in CI requires `protoc` and `grpcio-tools` installed in the CI environment with a pinned `protoc` version. This adds non-trivial CI maintenance cost. Committing the generated stubs is a common pattern for Python projects (the generated files are deterministic and clearly scoped to `services/ai-assistant/generated/`). The Kotlin side does not commit generated stubs because Gradle handles generation as part of the build — consistent with JVM conventions.

---

## Consequences

**Positive:**
- Typed proto contract prevents schema drift between Kotlin server and Python client.
- `suspend` functions on the Kotlin side and async stubs on the Python side — no threading workarounds.
- Server streaming for `ListDocuments` is memory-efficient regardless of workspace size.
- Port 9090 is never exposed through API Gateway — no accidental public surface.
- Cloud Map auto-registration removes manual service registration from the deployment process.

**Negative:**
- The `/proto/document.proto` file is a cross-language shared contract. Changes require regenerating stubs in both services and coordinating the change. This is manageable with two services; it becomes a governance concern if more services consume the same proto.
- The gRPC integration is only testable end-to-end once the Kafka consumer is implemented in the AI Assistant. Unit tests can use `grpcio-testing` mock stubs; integration tests require a live gRPC server on port 9090.
- Python async gRPC stubs are stable but less documented than the sync counterpart. Debugging async gRPC errors in Python requires understanding `asyncio` event loop interactions.
- Cloud Map must be provisioned before the first ECS deployment with gRPC. Forgetting this step causes a silent connection failure (DNS NXDOMAIN) rather than a clear error. The deployment checklist must note this dependency.

---

## Security boundaries

The gRPC port 9090 must not be reachable from outside the VPC. The ECS tasks security group must allow inbound TCP 9090 only from within the security group (service-to-service) or from the AI Assistant's specific task security group if separated. API Gateway has no path to this port. Confirm in Terraform: no ALB listener or NLB listener forwards to port 9090.

---

## Revisit when

- A third service consumes the Document Service gRPC API. At that point, consider publishing the proto file to a shared artifact repository (Buf Schema Registry) rather than referencing the monorepo path directly.
- ECS Service Connect matures further. It provides the same DNS-based discovery as Cloud Map with built-in retries and observability — a natural replacement at v1.5 if service count grows.
- The `ListDocuments` use case requires cursor-based resumption (e.g., re-index was interrupted mid-stream). Add a `page_token` field to `ListDocumentsRequest` at that point — the existing `modified_after` field covers incremental re-index but not mid-stream resumption.
