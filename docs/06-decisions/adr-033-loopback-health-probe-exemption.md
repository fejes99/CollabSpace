# ADR-033: Loopback-Only Exemption for Health Probe Endpoints

**Status:** Accepted
**Date:** 2026-07-12

---

## Context

`InternalTokenFilter` rejects every request with `401` unless it carries a valid `X-Internal-Token` — the shared secret API Gateway injects on every request it forwards. This is the trust boundary the whole filter chain depends on (ADR pending on the filter chain itself is the `security-filter` plan doc; see `docs/03-services/auth-workspace/plans/security-filter.md`).

Two paths under `/actuator/health/**` cannot carry that token, for different reasons than `.well-known/**` (which is exempted because API Gateway's own infrastructure calls it directly, never through the VPC Link):

- `GET /actuator/health/readiness` and `GET /actuator/health/liveness` have **no API Gateway route at all** — confirmed in Terraform. Only ECS's own container-level health check calls them, from *inside the same container*, per ADR-031 (`curl http://localhost:8080/actuator/health/readiness`).
- `GET /actuator/health` (root), by contrast, *is* routed through API Gateway (the `auth_health` route, public integration) and does carry the token on that path — it needs no exemption.

Without an exemption, ECS's own health check would fail every single check, marking the task permanently unhealthy and defeating ADR-031 entirely.

The naive fix — exempt `/actuator/health/**` by path alone — is too broad: it would let *any* caller on the path skip token validation, not just ECS's loopback probe.

---

## Decision

Exempt `/actuator/health/readiness` and `/actuator/health/liveness` from the internal-token check only when **both** conditions hold: the path matches, **and** the caller's raw socket address matches loopback (`127.0.0.1` or `::1`, checked via `org.springframework.security.web.util.matcher.IpAddressMatcher`, not string comparison — it correctly handles both IPv4 and IPv6 forms). `/actuator/health` (root) stays under normal enforcement.

This is the narrowest correct rule: the bypass only ever fires for a request that could only physically originate inside that exact container's own network namespace. It does not create a standing "anyone in the VPC can skip validation on this path" hole.

**The loopback check depends on `server.forward-headers-strategy` staying at its Spring Boot default, `NONE`, globally.** `X-Forwarded-For` cannot be used to determine "is this request really from loopback" — even via Tomcat's `RemoteIpValve`/trusted-proxy mechanism, which only defends against a *downstream* proxy injecting a fake hop, not against the *originating client* declaring `X-Forwarded-For: 127.0.0.1` itself. That's exactly the attacker's position here: any security decision built on a forwarded-IP header is spoofable by the party the check exists to stop. `IpAddressMatcher` reads the true, unrewritten `getRemoteAddr()` instead — safe, because it reflects the actual TCP socket peer, which an attacker can't fake regardless of what headers they send.

This has a real side effect: the login/registration audit `ip` field (`authentication.md`'s audit events table) cannot come from `getRemoteAddr()` either, since behind the ALB that would just be the load balancer's address for real traffic. That field is populated separately, by manually reading `X-Forwarded-For` at the log-line call site only — explicitly documented as untrusted, best-effort, for observability purposes, and never used for any security decision. This keeps two differently-trusted concerns (audit IP vs. loopback security check) on separate code paths instead of one shared, dual-purpose signal that would have to be trustworthy for both uses at once.

---

## Alternatives considered

**Exempt `/actuator/health/**` by path alone, no origin check.** Rejected — this is a real, standing bypass: any caller who can reach the service (e.g., another compromised task in the same VPC) skips token validation entirely on that path prefix, not just ECS's own probe.

**A separate `management.server.port`.** The more common production answer to this exact problem — it makes the trust boundary structural (the security group never exposes the management port) rather than something a filter has to reason about at request time. Not adopted here: it's a real infrastructure change (new port, security-group rule, task-definition wiring) for a problem the loopback-origin check already solves correctly, at zero infra cost. Worth revisiting if this service's health-check surface grows beyond two probe endpoints.

**Trust `X-Forwarded-For` for the loopback check, with Tomcat's `RemoteIpValve` trusted-proxy filtering.** Rejected as unsafe: the trusted-proxy mechanism only strips a forwarded header injected by an *untrusted downstream hop*, but the attacker here is the *originating client itself*, sending the request directly — nothing downstream to distrust. A client can simply set `X-Forwarded-For: 127.0.0.1` and the valve has no way to know that's a lie.

---

## Consequences

**Positive:**
+ ADR-031's container-level health check actually works — ECS can determine readiness without ever needing a copy of the internal token
+ The bypass is structurally narrow: path *and* origin, both required, so it cannot be widened by accident to cover more traffic than intended
+ Reuses `IpAddressMatcher`, a Spring Security primitive already correct for IPv4/IPv6 loopback forms, instead of hand-rolled string comparison
+ Keeps the audit-IP concern (best-effort, observability-only) architecturally separate from the loopback security check (must be unspoofable) — a bug in one cannot silently weaken the other

**Negative:**
− `server.forward-headers-strategy=NONE` is a global setting this loopback check depends on — anyone changing it for an unrelated reason (e.g., to fix the audit `ip` field, or to add a CDN in front of the ALB) would silently break this security boundary without an obvious signal that they've done so
− The audit trail's `ip` field is explicitly untrustworthy (spoofable via `X-Forwarded-For`) — acceptable because it's documented as observability-only, but a future reader building something that assumes otherwise would be wrong
− Two enforcement mechanisms for one concern (path-only for `.well-known/**`, path+origin for health probes) — a developer adding a fourth exemption category has to figure out which shape it needs, not just copy the nearest example

---

## Revisit when

- This service's health-check surface grows beyond the two current probe endpoints, or a second internal-only endpoint needs the same treatment — a separate `management.server.port` becomes proportionate at that point, not before
- `server.forward-headers-strategy` needs to change for any other reason — re-verify this ADR's loopback check still holds under the new configuration before merging
- Any future service in this project needs an equivalent "trust the caller only if it's really local" check — reuse this exact `IpAddressMatcher` + `forward-headers-strategy=NONE` pattern rather than re-deriving it
