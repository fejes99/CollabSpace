# ADR-004: MongoDB Atlas for the Document Service

**Status:** Accepted  
**Date:** 2026-04-25

---

## Context

The Document Service needs a document store. The primary requirements are:

- **Schema flexibility.** The document content model is expected to grow —
  new field types, metadata, editor-specific structures. A rigid relational
  schema would require migrations for every structural change; a document
  model accommodates evolution without a migration step.
- **JSON-native query model.** Documents are JSON at every layer (API,
  transport, storage). A store that treats JSON as a first-class value —
  with native operators, aggregation pipeline, and indexed fields within
  nested structures — fits better than a relational store treating JSON as
  an opaque blob.
- **Cost.** The project targets $0–5/month. Any choice with a minimum
  instance cost above ~$5/month is a hard skip regardless of architectural
  fit.

---

## Decision

Use **MongoDB Atlas M0** (free-tier cluster, 512 MB, no expiry).

DocumentDB was eliminated by cost before architectural evaluation: it has
no free tier and a minimum cost of approximately $50/month. This is not an
architectural dismissal — DocumentDB uses the same MongoDB wire protocol
and would be architecturally equivalent. It is a budget constraint applied
as a hard filter. At the $0–5/month target, DocumentDB is not a viable
option.

Atlas M0 satisfies all three requirements: schema flexibility, JSON-native
query model, and zero idle cost. The 512 MB storage ceiling is not a
meaningful constraint for a development workload serving a team of 5–15
people — expected document storage at this scale is well within single-digit
megabytes during the project lifetime.

**Security note on M0:** Atlas M0 clusters do not support VPC peering or
private endpoints. Traffic between the Document Service (running in AWS) and
Atlas travels over the public internet, secured by TLS and IP allowlisting.
This is an accepted trade-off for the free tier. A production deployment
would use Atlas Dedicated (M10+) with VPC peering. See Revisit When.

---

## Alternatives Considered

### AWS DocumentDB

Architecturally equivalent to MongoDB (MongoDB 4.x wire protocol
compatibility). AWS-native: VPC-native, IAM authentication, CloudWatch
metrics without additional configuration.

Rejected on cost: no free tier, minimum ~$50/month for the smallest
instance. Incompatible with the $0–5/month budget target.

### PostgreSQL with JSONB

PostgreSQL supports JSONB columns with GIN indexes and JSON operators.
The Auth & Workspace service already uses PostgreSQL, making this a
zero-new-dependency option.

Rejected because: JSONB loses MongoDB's native aggregation pipeline, Atlas
Search, and first-class array operators. More importantly, it conflates two
distinct workloads — transactional relational data (users, memberships) and
flexible document storage — onto one database instance, coupling their
scaling and failure profiles. The document store should be independently
evolvable.

### Self-managed MongoDB on EC2

Free to run (EC2 instance cost only). Full control over version, config,
and storage.

Rejected because: operational overhead (manual patching, backup management,
replication configuration for durability) is out of scope for a learning
project at this stage. Atlas M0 eliminates this entirely. If the learning
goal were database operations, self-managed would be worth the cost; here
the learning goal is the application layer.

---

## Consequences

**Positive:**
+ Permanent free tier (512 MB) — no expiry, no surprise cost
+ Schema flexibility without migrations during iterative document model
  development
+ JSON-native query model matches the service's data shape end-to-end
+ Managed backups, monitoring, and upgrades at no additional cost on M0
+ Zero operational overhead for the database layer

**Negative:**
− M0 to M10 (the next tier) is a pricing cliff: ~$0 to ~$57/month with no
  intermediate option. Outgrowing M0 forces a significant cost step
− No VPC peering on M0; Document Service → Atlas traffic travels over
  public internet (TLS + IP allowlist mitigates but does not eliminate
  network exposure)
− 512 MB hard storage limit on M0. No in-place upgrade path; migration to
  a paid tier requires a cluster migration, not a resize
− DocumentDB was not evaluated architecturally — the decision rests on cost
  elimination, not a comparative technical evaluation

---

## Revisit When

- Atlas M0 storage approaches 400 MB (80% of ceiling) — begin planning
  migration to M10 or self-managed
- A production deployment requires VPC-native connectivity (M10+ required
  for private endpoints)
- DocumentDB free-tier offering becomes available
- The document content model stabilises and schema flexibility is no longer
  a differentiating requirement (at that point PostgreSQL JSONB becomes a
  viable simplification)
