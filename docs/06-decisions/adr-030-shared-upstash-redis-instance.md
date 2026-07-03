# ADR-030: Shared Upstash Redis Instance, Convention-Based Isolation

**Status:** Proposed
**Date:** 2026-07-03

---

## Context

auth-workspace requires Redis for JWT blocklist storage (revoked/logged-out tokens — see the `feat/auth/security-filter` plan). realtime-service will require Redis for pub/sub coordination across WebSocket instances once it moves past its walking skeleton (ADR-005). Both depend on Upstash Redis, already selected as the platform's Redis provider.

This is the same shape of decision ADR-005 made for PostgreSQL: two services needing the same underlying technology. That ADR's answer was one shared RDS/Neon instance, split into per-service databases (`auth_db`, `vector_db`) with separate least-privilege users — a cost optimization avoiding a second paid instance.

Redis ACL (`ACL SETUSER`) would be the direct analog to Postgres's per-database least-privilege users, but Upstash restricts ACL to paid plans only ("ACL is available on all paid databases" — Upstash security docs). There is no free-tier path to platform-enforced per-service isolation. Given the project's $0–5/month budget target, this ADR accepts weaker, convention-based isolation instead of paying for it.

---

## Decision

One shared Upstash Redis instance (`collabspace-dev`, free tier — 500K commands/month, 256MB), used by auth-workspace now and realtime-service later. Isolation between services is enforced by **naming convention only**, not by the platform:

- Each service's keys are namespaced under an explicit prefix — e.g. `auth-workspace` → `blocklist:*`; `realtime-service` → its own presence/coordination prefix when that service is built
- Both services connect using the **same** shared credentials (the default Upstash user) — there is no per-service credential or access restriction
- Isolation is a code-review and discipline concern: a bug in either service's Redis client code *can* read or write the other service's keys; nothing in Redis itself prevents it

---

## Alternatives considered

**ACL-based isolation on a shared instance** (the originally drafted approach). Rejected: Upstash restricts ACL to paid databases — fixed tiers start at $10/month, and the free tier has no ACL path at all. Conflicts directly with the $0–5/month budget target.

**Separate Upstash instance per service.** Rejected, but kept as the fallback (see Revisit When). Upstash's free tier permits up to 10 databases per account, so this would still cost nothing — but it diverges from the ADR-005 Postgres pattern without a strong enough reason: the two services' Redis usage (a small TTL'd blocklist vs. pub/sub fan-out) doesn't obviously demand physical separation, and one shared instance is simpler to provision/monitor/tear down per dev-environment cycle (ADR-022).

**Shared credentials, no namespace convention at all.** Rejected. Costs nothing extra to adopt an explicit prefix per service, and it's the minimum discipline needed to keep the keyspace legible — and it makes a future move to ACL (if ever justified) additive rather than a keyspace redesign.

---

## Consequences

**Positive:**
+ $0 cost — stays within the free tier and the project's budget target
+ One connection endpoint to provision, monitor, and tear down per dev-environment cycle (ADR-022), not two
+ Consistent naming convention makes a future move to ACL (if ever justified) a matter of applying `~prefix:*` restrictions to already-conventional keys, not a keyspace redesign

**Negative:**
− No platform-enforced isolation: a bug or bad deploy in either service's Redis client code can read or write the other service's keys. This is a real step down from the Postgres precedent (ADR-005), which does have platform-enforced least-privilege grants — accepted here specifically because the equivalent Redis control costs money
− Both services share the same account-wide command quota (500K/month) and availability — an outage or quota exhaustion from one service's usage affects the other
− If a security incident or audit later requires proof of isolation between services, convention alone won't satisfy that — the decision would need revisiting under incident pressure rather than in advance

---

## Revisit when

- Realtime-service's pub/sub command volume is measured and threatens the shared 500K/month quota (mirrors cost-strategy.md's existing "measured Neon load" trigger for Postgres) — check Upstash's dashboard usage metrics once realtime-service is live
- A security incident, near-miss, or audit specifically requires platform-enforced isolation between services — re-evaluate paying for ACL, or fall back to separate free instances, at that point
- Upstash introduces free-tier ACL or changes its database-count limit, removing the cost constraint driving this decision
