# ADR-032: Membership Claims Staleness and Revocation

**Status:** Proposed
**Date:** 2026-07-03

---

## Context

The fat JWT design (`authentication.md`) bakes a user's full `memberships` array (workspaceId + role) into the access token at issuance, so downstream services can authorize requests without a database call. The token lives 15 minutes. `authentication.md:13` already accepts that role changes don't take effect until the token naturally expires — "acceptable" for a collaboration product where role changes are infrequent.

That framing conflates two risk directions that are not actually symmetric:

- **Grants** — a user creates a workspace, accepts an invite, or promotes themselves. A stale token here just means a UX papercut (the user briefly can't use something they're now entitled to). And a "wait for natural expiry" story doesn't even apply cleanly: a *brand new* workspace isn't in any prior token in any form, stale or otherwise, so there's nothing to "become fresh" without an explicit action.
- **Revocations** — a user is removed from a workspace, demoted, or the workspace is deleted entirely. A stale token here means the token keeps granting access it shouldn't, for up to 15 minutes, with no server-side way to shorten that window. This is a real security exposure, not a papercut, and unlike logout — which already has the `blocklist:<jti>` mechanism (`authentication.md` — Token revocation) — there is currently no equivalent for membership changes.

A workable design needs to treat these two cases differently rather than applying one blanket staleness tolerance to both. The dividing line for *which mechanism* applies, however, turns out not to be grant-vs-revocation directly — it's whether the affected user is the one making the request right now. See Decision.

---

## Decision

The mechanism depends on one axis: **is the user affected by a change the same user making the HTTP request right now?** Self-directed changes can hand back a fresh token synchronously, in the same response. Other-directed changes cannot — the affected user isn't present in that request/response cycle — so they need a mechanism the affected user's *own* next request discovers on its own, regardless of whether that change grants or revokes access.

**Self-directed: reissue on mutation.** Any endpoint where the calling user is also the one whose access changes (create workspace, accept invite, self role change, self-demote) returns a fresh access token in its response, or the client immediately calls `POST /v1/auth/refresh` afterward. No wait, ever. This requires no new infrastructure — it's a DB read of current memberships plus an RS256 signature, both cheap relative to the bcrypt cost already paid at login. It applies equally whether the self-directed change is a grant or a revocation.

**Other-directed: inline versioned-claims check.** Any change where the actor and the affected user differ — an admin promotes or demotes *someone else*, removes a member, deletes a workspace — sets a per-user Redis marker for the **affected** user, not the actor: `membership-changed-at:<affectedUserId>` (a `SET`, last-write-wins, not an increment). This applies whether the change grants or revokes access — a promotion made to someone else needs the same "tell them on their next request" mechanism a removal does, since neither actor-side reissue nor any other channel reaches them synchronously. On every authenticated request, each service compares this marker against the access token's `iat` claim (no new JWT claim needed — `iat` already exists) alongside the `blocklist:<jti>` check PR 7 already adds. If the marker is **greater than or equal to** `iat` — see Mechanics for why not strict `>` — the service rejects with a distinct `401 claims-stale` instead of authorizing against stale data. The client's existing "access token invalid → refresh → retry once" interceptor (already required for ordinary token expiry) handles this identically — no manual refresh button, no polling.

**Mechanics:**

- **Refresh must re-derive claims, never copy them forward.** `POST /v1/auth/refresh` re-queries current memberships from Postgres on every call and signs the new token from that live read — it must never construct the new token by copying the previous token's claims forward with a new `iat`/`jti`/`exp`. This is what actually closes the loop: without it, a `claims-stale`-triggered refresh would produce a token with a fresh `iat` (passing the staleness check) while still carrying the stale memberships that triggered the rejection — turning a staleness bug into a privilege bug. This is not an extra cost specific to this design: refresh already hits Postgres to validate and rotate the refresh token row (`authentication.md` — Token refresh, steps 3–5), so reading current memberships in the same round trip adds one query to an already DB-bound call, at a frequency of once per token lifetime rather than once per request.
- **Only auth-workspace writes the marker.** `membership-changed-at:<userId>` is written exclusively by auth-workspace, the sole owner of membership and role data (`authorization.md` — Multi-service authorization: "The Auth & Workspace service is the only service that manages memberships and roles"). No other service ever sets this key. This is what keeps the `iat`-vs-marker comparison free of cross-service clock skew — both values come from the same service's clock.
- **Comparison must be `>=`, not `>`.** `iat` is second-granularity per RFC 7519 (`NumericDate`), and the write-ordering guarantee below establishes sequence, not a distinct timestamp — a marker write and a token mint can land in the same wall-clock second. A strict `>` would let a same-second race slip a stale token through as valid; the check treats a tie as stale.
- **TTL on the marker:** `access_token_max_lifetime + buffer` (not indefinite). Past that window, every token that could have predated the change has either expired or been refreshed, so the marker has done its job and can be reclaimed. This differs from `blocklist:<jti>`, which is scoped per-token; this is a per-user signal, so it must outlive the token TTL rather than match it.
- **Workspace deletion:** fans out to one write per affected member (`membership-changed-at:<userId>` for each), pipelined into a single Redis round trip — not a single per-workspace key, since the check is always per-user.
- **Write ordering:** any mutation that both bumps the marker and reissues a token (the self-demote case) must do so DB commit → Redis marker bump → **then** mint the new token. Reversing the last two steps means the freshly issued token's `iat` could tie or predate the marker it just set, self-invalidating on first use — the `>=` comparison above is a backstop for the same-second case, not a substitute for correct ordering.
- **Fail-open:** a Redis outage causes the version check to pass (fail open), matching the existing accepted policy for `blocklist:<jti>` (`authentication.md:131`). Both checks now degrade together on a single Redis outage — this is a wider blast radius than before, but consistent with the existing risk posture rather than a new one.
- **Non-atomicity:** the DB write (role change) and the Redis marker bump are two separate operations with no distributed transaction between them. If the DB commits but the Redis write fails, the change is real but unenforced until the token's natural expiry — same class of gap the blocklist write already has. Accepted as best-effort-with-logging at this project's scale; closing it fully would need an outbox pattern — a data-layer concern (Category C, per `roadmap.md`), not an operational one.
- **Client dependency.** The "no manual refresh button" outcome assumes the frontend implements a generic 401-triggers-refresh-then-retry-once interceptor. That interceptor doesn't exist yet — frontend is out of scope for the project's current stage (`CLAUDE.md`). The backend mechanism (marker + inline check) is independent of when that lands, but the seamless UX it enables is contingent on it.

---

## Alternatives considered

**Accept the 15-minute staleness as-is, for both directions.** Rejected for revocations — a real, undocumented security exposure that the existing "role changes are infrequent" framing doesn't actually justify once you separate grants from revocations.

**Shorten the access token TTL (e.g., 15 → 5 minutes) as the fix.** Rejected. It does nothing for the grant side — a brand-new workspace is still absent from the token regardless of how short the TTL is. And once the versioned-claims check exists, it makes the *revocation* side's TTL length irrelevant too: a stale token is caught on its next request regardless of how much life it has left. Shortening TTL only mattered as a fallback in the absence of an inline check; with the check in place, it buys nothing and adds refresh traffic for no benefit.

**Per-user token floor for every membership change** (a global `user-token-floor:<userId>`, rejecting any token with `iat` before it — i.e., force-invalidate every token this user holds, everywhere). Rejected as the default mechanism: it's per-user, not per-workspace, so being removed from workspace B would also force-expire an otherwise-valid session in workspace A. Kept in reserve for a coarser, intentionally-blunt use case — see Revisit when.

**Event-driven claims caching** (auth-workspace publishes `membership.changed` events via Kafka; each service maintains a locally materialized, asynchronously-updated authorization cache instead of trusting JWT claims at all). Rejected for now — this removes the staleness problem at the architecture level but is a materially bigger design than the problem currently warrants: event consumers, cold-start cache warming, and eventual-consistency handling per service. Noted as a future direction, not adopted.

---

## Consequences

**Positive:**
+ Self-directed changes are instant — no wait, ever
+ Other-directed changes — including grants made to someone else, not just revocations — are caught on the affected user's very next request, not after up to 15 minutes
+ Cheap: one additional Redis GET per request, on the same Upstash instance and same request path PR 7's blocklist check already uses (ADR-030) — no new infrastructure
+ No new client-visible interaction pattern — reuses the refresh-and-retry flow the client already needs for ordinary token expiry

**Negative:**
− Adds a second Redis dependency to the authenticated hot path in every service, alongside the blocklist check — a Redis outage now affects two enforcement mechanisms at once, not one
− Requires strict write-ordering discipline (DB → Redis → token) at every mutation site that reissues a token, and correctly classifying whether a mutation is self- or other-directed; getting either wrong either self-invalidates a freshly issued token or leaves an other-directed change silently unenforced
− The DB write and Redis marker bump are not transactionally atomic — a partial failure leaves a change unenforced until natural token expiry, same accepted-risk shape as the existing blocklist gap
− Workspace deletion with many members means a write fan-out proportional to member count; fine at this project's scale, a real consideration at a much larger one
− The seamless, no-manual-refresh UX depends on a frontend interceptor that doesn't exist yet (frontend is currently out of scope) — the backend mechanism is complete without it, but its benefit isn't fully realized until that lands

---

## Revisit when

- Per-service Redis reads on the authenticated hot path are measured as a bottleneck, or sub-second cross-service consistency becomes a real requirement — evaluate event-driven claims caching (Kafka `membership.changed` + per-service materialized authorization cache) as the architectural replacement, not an incremental fix.
- Password reset (`authentication.md` — Password reset, currently out of scope for v1) is implemented — promote the per-user token floor from "reserved, unused" to active use: a password reset should force-invalidate every session the user holds, which is exactly the coarse, all-sessions behavior this ADR rejected as the *default* mechanism but is the *correct* one for that specific case.
