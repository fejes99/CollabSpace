# Technology Choices

This document records every technology decision for CollabSpace v1 and the rationale behind each. Where a non-trivial decision was made, the relevant ADR is cited. ADRs contain the full alternatives-considered analysis; this document is the summary view.

---

## Per-Service Stack

| Service | Language | Framework | Database | Sync API | Async |
|---|---|---|---|---|---|
| Auth & Workspace | Java 21 | Spring Boot 3 | PostgreSQL (RDS) + Redis (Upstash) | REST → API Gateway (HTTP API) | SNS publisher |
| Document Service | TypeScript · Node 22 | Express | MongoDB Atlas | REST → API Gateway (HTTP API) | SNS publisher · SQS consumer |
| Realtime Service | TypeScript · Node 22 | ws | Redis (Upstash) — coordination only | WebSocket → ALB | SQS consumer |
| AI Assistant | Python 3.13 | FastAPI | PostgreSQL + pgvector | REST → API Gateway (HTTP API) | Kafka consumer |
| Notification | TypeScript · Node 22 | — (Lambda runtime) | — | — | SQS trigger (Lambda) |

---

## Rationale by Service

### Auth & Workspace

Java 21 with Spring Boot 3 is the most battle-tested stack for auth and identity. Spring Security's JWT support, Spring Data JPA, and Bean Validation reduce the surface area for auth bugs — the area where a learning project is most likely to make costly mistakes. The two concerns (authentication and workspace membership) are combined into one service because they are tightly coupled at the data level: workspace roles are enforced by the same token-validation path that handles login. → **ADR-002**

PostgreSQL is the natural fit for relational, transactional data: users, workspaces, memberships, roles. Redis (Upstash) serves two purposes here: JWT blocklist for logout (US-03) and session caching. Upstash is used over a self-managed Redis instance to stay within the free-tier cost target. → **ADR-005**

### Document Service

Node.js with TypeScript and Express is chosen for its JSON-native I/O model and the ecosystem fit with MongoDB. Documents are schema-flexible by nature — a rigid SQL schema would require migrations for every structural change to the document model. Express is intentionally minimal; the service does not need the opinions that come with a heavier framework. → **ADR-004**

MongoDB Atlas is used over a self-managed MongoDB instance for the same reason as Upstash Redis: operational overhead is out of scope for a learning project at this stage. The Atlas free tier (512 MB) is sufficient for v1. → **ADR-004**

The Document Service is both a SNS publisher (it fires `document.updated` on save, triggering US-15 fan-out) and a SQS consumer (it receives background indexing triggers from the AI pipeline). This makes it the hub of the async flow.

### Realtime Service

The Realtime Service uses the `ws` library directly rather than Socket.IO. Socket.IO's fallback transports, rooms abstraction, and auto-reconnect logic are valuable in production at scale but add opacity that conflicts with the learning goal. Managing the WebSocket lifecycle explicitly is the point. → **ADR-005**

Redis pub/sub (via Upstash) is used as a coordination layer, not a primary store. When the service runs as multiple EC2 instances, a message arriving on one instance must be broadcast to clients connected to other instances. Redis pub/sub is the standard solution for this fan-out within the Realtime Service itself. If only one instance is running (as in dev and early staging), Redis pub/sub is still used for consistency.

The Realtime Service runs on EC2 rather than ECS Fargate. EC2 gives predictable host lifecycle (no scheduler-driven task replacement during active connections), direct SSH access for debugging WebSocket state, lower cost for long-running idle connections (Fargate bills per vCPU-second; a WebSocket server with low traffic still holds compute continuously), and educational visibility into kernel-level networking. The trade-offs — manual patching, no auto-scaling — are documented in ADR-005. → **ADR-005**

### AI Assistant

Python 3.13 with FastAPI is the natural choice for an ML-adjacent service: the embedding libraries (sentence-transformers, or the Anthropic SDK for embeddings), pgvector drivers, and async I/O support are all first-class in the Python ecosystem. FastAPI's async-native design matches the I/O-heavy pattern of making LLM API calls. → *See [ADR index](#adr-index) — Claude API ADR planned for AI Assistant implementation stage.*

PostgreSQL with the pgvector extension stores document embeddings alongside metadata. This avoids introducing a dedicated vector database (Pinecone, Weaviate) at a stage where the data volume does not justify the operational cost. pgvector is sufficient for a workspace of 5–15 people generating hundreds to low thousands of document chunks. The AI Assistant uses a separate database (`vector_db`) on the same RDS instance as Auth & Workspace (`auth_db`), with separate RDS users and least-privilege grants between them. This is a cost optimisation for v1 — co-location avoids a second RDS instance. Revisit criteria and split conditions are documented in ADR-005. → **ADR-005**

The AI indexing pipeline fetches document content via the Document Service REST API (`GET /documents/{id}`) when processing a Kafka event. Direct MongoDB access would violate service ownership boundaries; embedding the full document body in the Kafka event would bloat the event stream for large documents. This creates a soft dependency: if the Document Service is unavailable when the AI consumer processes an event, indexing fails for that document. Kafka's consumer retry semantics mitigate this — the event is replayed once the Document Service recovers. The full pattern, sequence, and trade-offs are documented in [service-communication.md](service-communication.md). Service-to-service authentication for this call is an open design question tracked there as a placeholder ADR.

Kafka (self-managed on EC2) is used for the AI indexing pipeline rather than SNS/SQS. Indexing is a high-volume, replayable, ordered stream: when a document is updated, the AI service must process chunks in order and must be able to replay from a checkpoint if the indexing job fails mid-document. SNS/SQS does not provide replay or ordering guarantees at this granularity. → **ADR-003**

### Notification Service

AWS Lambda (Node.js 22) is used because notifications are event-driven, stateless, and infrequent. There is no reason to run a persistent process for a function that executes once per document save. Lambda eliminates the need to provision, scale, or pay for idle compute for this concern. → **ADR-005**

The Lambda is triggered by SQS (not SNS directly) to benefit from SQS's retry behaviour and dead-letter queue support. A failed notification delivery is retried automatically without custom retry logic in the function.

---

## Cross-Cutting Choices

### Monorepo

All five services and the infrastructure code live in a single repository. → **ADR-001**

### Infrastructure

Terraform with a module-per-concept structure manages all AWS resources. GitHub Actions with OIDC authentication (no long-lived credentials) runs CI/CD. LocalStack emulates AWS services locally so that the full async event flow (SNS → SQS → Lambda) can be exercised without an AWS account during development.

### Messaging topology

SNS + SQS handles the general fan-out pattern (Document Service → Notification + Realtime). Kafka on EC2 handles the AI indexing pipeline. The two are intentionally separate: SNS/SQS is operationally simpler and sufficient for low-volume fan-out; Kafka provides the replay and ordering guarantees the AI pipeline requires. → **ADR-003**

### Compute mix

| Service | Compute | Reason |
|---|---|---|
| Auth & Workspace | ECS Fargate | Stateless, containerised, scales to zero |
| Document Service | ECS Fargate | Stateless, containerised, scales to zero |
| AI Assistant | ECS Fargate | Stateless, containerised, scales to zero |
| Realtime Service | EC2 | Predictable host lifecycle, lower idle cost, debugging access |
| Kafka broker | EC2 | Stateful; broker state must survive compute replacement |
| Notification | Lambda | Stateless, event-driven, no idle cost |

→ **ADR-005**

---

## ADR Index

| Decision | ADR |
|---|---|
| Monorepo vs. polyrepo | ADR-001 |
| Auth & Workspace as a combined service | ADR-002 |
| Broker strategy: SNS/SQS + Kafka | ADR-003 |
| MongoDB Atlas for the Document Service | ADR-004 |
| Compute heterogeneity (Fargate + EC2 + Lambda) | ADR-005 |
| Claude API as AI backend | *(planned — AI Assistant implementation stage)* |
