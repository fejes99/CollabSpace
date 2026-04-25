# ADR-002: Auth and Workspace as a Single Combined Service

**Status:** Accepted  
**Date:** 2026-04-25

---

## Context

CollabSpace requires both an identity layer (user registration, login,
logout, JWT issuance) and a workspace layer (workspace lifecycle,
membership, roles, invitations). These could be two services or one.

The deciding factor is the type of coupling between them. Two types exist:

**Type A — Network coupling.** If the JWT carries only `{ userId }`, every
downstream service must call back to a Workspace service to resolve the
caller's role before enforcing authorization. This creates a runtime
dependency on Workspace availability for every authenticated request across
the system. This type of coupling can be eliminated by design: a fat JWT
carrying `{ userId, memberships: [{ workspaceId, role }], exp }` lets every
service enforce authorization locally from token claims. Type A is not the
reason to combine these services.

**Type B — Data-model coupling.** The ACID guarantee operates at two levels:

- **Within-Workspace invariants.** Aggregate-local rules like "a workspace
  must always have at least one admin" require atomic check-and-mutate
  semantics on the membership relation. A race between two concurrent
  admin-removal requests can violate this invariant without a transaction.
  This applies regardless of where the Workspace service lives.

- **Auth-Workspace consistency.** JWT issuance reads current membership
  state to embed role claims in the token. If Auth and Workspace are
  separate services, a race window exists between membership revocation and
  token issuance: a user whose access was revoked could receive a new token
  containing the revoked claims if the revocation event has not yet
  propagated. Combining keeps the membership read and token signing within
  a single transaction boundary.

**Security.** In a separated architecture, JWT issuance must trust
assertions from the Workspace service about memberships. The authentication
path's security becomes dependent on the integrity and availability of
another service. Combining Auth and Workspace keeps identity and
authorization within a single trust boundary, enforced by database-level
access controls rather than inter-service trust.

---

## Decision

Deploy Auth and Workspace as a single Spring Boot application backed by a
single PostgreSQL instance. Internally they are two separate logical modules
with strict boundaries:

- Two PostgreSQL schemas: `auth_schema` (users, credentials, token
  blocklist) and `workspace_schema` (workspaces, memberships, invitations)
- No cross-schema foreign keys. Workspace references users by `userId`
  (UUID) only — a value, not a relational constraint
- Auth exposes a single internal Java interface (`AuthService`) that
  Workspace calls to resolve user identity. Workspace never touches
  `auth_schema` tables directly
- Boundaries enforced by ArchUnit tests in CI: a failing ArchUnit rule
  blocks merge
- Flyway migrations in separate folders: `db/migration/auth/` and
  `db/migration/workspace/`

This is the **extractable monolith** pattern. The internal structure is
designed so that splitting into two services later requires deploying two
artifacts and replacing the internal `AuthService` call with an HTTP client
— not rewriting the data model.

**JWT design:** tokens carry fat claims —
`{ sub, userId, memberships: [{ workspaceId, role }], iat, exp, jti }` —
so downstream services enforce authorization locally without calling Auth.
Token TTL is 15 minutes.

**Revocation path:** on a `membership.revoked` event, the Auth service
writes the affected user's token IDs (`jti` values) to a Redis blocklist
with TTL equal to the remaining token lifetime. JWT validation middleware
on every protected request checks the blocklist before accepting the token.
This is coarse-grained: all of the user's active tokens are invalidated,
forcing re-login. The new JWT issued on next login carries current
post-revocation memberships. Fine-grained per-workspace claim revocation
(invalidating one workspace's claims while keeping others valid) is
deferred to v2. If the Redis write fails (network partition or Upstash
outage), the blocklist entry is not created and the revoked token remains
valid until expiry (max 15 minutes). This is a known fail-open gap;
a dual-write fallback to PostgreSQL is the v2 mitigation.

---

## Alternatives Considered

### Split: Auth Service + Workspace Service

Real advantages at production scale:

- **Independent scaling.** Auth handles login spikes; Workspace handles
  membership operations. At scale these profiles diverge.
- **Multi-product reuse.** A standalone Auth service can authenticate users
  across multiple products without coupling to workspace concepts.
- **Compliance isolation.** Regulated environments may require credential
  storage in a separately auditable service with restricted access.
- **Conway's Law alignment.** A dedicated platform or identity team can own
  Auth independently.

Rejected for this project because none of the above apply: no scaling
pressure, no second product (YAGNI), not a regulated workload, one
developer. The distributed transaction complexity imposed by splitting would
be a constant tax on correctness for no material benefit at this stage.

---

## Consequences

**Positive:**
+ ACID enforcement of cross-domain invariants (last-admin protection, role
  uniqueness, idempotent invites, Auth-Workspace consistency on token
  issuance) without distributed transactions or sagas
+ Fat JWT means downstream services never call back to Auth at request time
  — no hidden runtime dependency on Auth availability for authorization
+ Single trust boundary for identity and authorization; no inter-service
  trust assumptions in the auth path
+ Internal module boundaries (ArchUnit, separate schemas, no cross-schema
  FKs) make the split path well-defined and estimable (~1–2 weeks when
  triggered)
+ Simpler operational profile: one container, one database connection pool,
  one deployment pipeline

**Negative:**
− Workspace membership changes are stale in the JWT until token expiry
  (15 min). A user added to a workspace must re-authenticate to see it.
  Removal is handled by full token revocation (forced re-login), not
  claim-level revocation — coarser than ideal
− Fat JWTs grow with workspace membership count. A user in 20 workspaces
  carries a larger token on every request; token size is unbounded by
  this design
− Single service means Auth and Workspace share a deployment and failure
  domain. A bug in workspace membership logic can affect login
− Revocation correctness depends on Redis availability. If Redis is
  unreachable when a revocation is written, the blocklist entry is not
  created and the token remains valid until expiry. See Revisit When
− Internal package structure must enforce Auth/Workspace separation
  (separate packages, no cross-imports, calls through service interfaces
  not direct repository access). This is ongoing design discipline, not a
  one-time setup. The cost is paid continuously; the benefit is current
  transactional integrity, not free future extraction

---

## Revisit When

- A second product (mobile app, public API, sibling SaaS) needs
  authentication independently of workspace concepts
- Compliance requirements mandate credential storage in a separately
  auditable system with restricted access controls
- A dedicated identity or platform team forms with primary ownership of
  authentication
- Auth request volume diverges from Workspace volume by 50× or more AND
  auto-scaling within a single service cannot accommodate the difference
- Redis blocklist availability becomes a recurring reliability concern
  (signals that the coarse revocation mechanism needs a more durable
  backing store)
