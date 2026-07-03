# ADR-031: ECS Container-Level Health Check for Traffic Readiness

**Status:** Accepted
**Date:** 2026-07-03

---

## Context

Cloud Map (ADR-026) currently tracks task health purely from ECS task state: a task is "healthy" (registered in DNS, eligible for API Gateway VPC Link routing) the instant ECS reports it `RUNNING`, and "unhealthy" only on `STOPPED`/`DRAINING`. This has no visibility into whether the *application* inside the container has actually finished starting.

This gap was not theoretical — it was reproduced live while deploying the `redis-client` PR. auth-workspace's measured cold start (JPA + Flyway migrations + JWT key load from SSM + Tomcat) took **122 seconds**. During a rolling deployment, the new task was reported `RUNNING` (and therefore Cloud Map-eligible) almost immediately, while the old task was simultaneously deregistered — producing a window of roughly two minutes where API Gateway could round-robin to an IP that wasn't actually listening yet. This surfaced as real `503 Service Unavailable` responses from API Gateway itself, not from the application.

The `ecs-service` module's own code comment has flagged this exact gap since it was written: *"Adding a container-level healthCheck block to the task definition (see ADR-026 consequences) improves signal quality here."* This ADR substantially reduces that gap — it does not fully close it; see the empirical results below.

---

## Decision

Add an optional container-level ECS health check to the `ecs-service` module (`var.health_check_command`, default `null` — opt-in per service, since not every service using the module has an equivalent endpoint yet). For auth-workspace, wire it to:

```
CMD-SHELL curl -f http://localhost:8080/actuator/health/readiness || exit 1
```

`interval=5s`, `timeout=3s`, `retries=2`, `start_period=150s` (comfortably past the measured 122s cold start — a `start_period` shorter than real startup time would cause ECS to kill tasks that are still legitimately starting, which is worse than the problem being solved).

Also set `deployment_minimum_healthy_percent=100` (module default is 0) for auth-workspace. This is not optional alongside the health check — without it, ECS is still free to stop the old (healthy) task before the new one is confirmed healthy, which was reproduced live as a real regression during this ADR's own rollout (see Empirical results).

This also corrects a naming mistake from the `redis-client` PR: `db` was placed in the `liveness` group, but semantically it belongs in `readiness`. **Readiness** answers "should traffic be routed here right now" — a DB outage should stop routing, but the container shouldn't be killed and restarted, since restarting never fixes an external Neon outage. **Liveness** answers "should this process be killed and restarted" — reserved for internally-fixable brokenness, and left at Spring Boot's default (no external dependency checks) for exactly that reason. The ECS health check above correctly targets `readiness`, not `liveness`.

---

## Empirical results (two live deployments, tested after merge)

The first deployment (health check only, `minimum_healthy_percent` still 0) reproduced a **new** failure mode: ECS stopped the old healthy task before the new one passed its check, at all — a regression, not an improvement, until `minimum_healthy_percent=100` was added.

With both fixes in place, two further live deployments showed:
- The old task now stays up throughout — no longer a guaranteed zero-healthy-task window
- But a **~2-minute intermittent flicker remains** (roughly 50% request failure rate, not 100%): the new task's ECS health status stays `UNKNOWN` — never `UNHEALTHY` — for its entire `start_period` window, and Cloud Map/API Gateway routes to it anyway during that time, alongside the old task
- Tightening `interval`/`retries` (15s/3 → 5s/2) made **no measurable difference** to this flicker's duration across two comparable test runs — both matched the ~122s real cold start almost exactly. This confirms the mechanism: `start_period` suppresses `UNHEALTHY` regardless of `interval`/`retries`, so those settings only matter for a task's post-startup lifetime, not its startup window. Kept anyway — they're the correct setting for detecting a task that goes unhealthy *after* a successful deploy (e.g., an external dependency dying hours later), where `start_period` no longer applies.

**Conclusion: this ADR reduces deployment-time failure from guaranteed 100% for ~2 minutes to roughly 50% for ~2 minutes. It does not achieve zero-gap deployment, and closing the remainder is explicitly out of scope** (see Alternatives).

---

## Alternatives considered

**Do nothing — accept the original deployment-time 503 window.** Rejected. A reproducible, guaranteed-failure window on every deployment is worse than a reduced, probabilistic one, even though the fix isn't complete.

**Route 53 health checks against a public endpoint.** Rejected on cost ($0.75/endpoint/month, explicitly called out as something this design avoids) and unnecessary indirection.

**`desired_count = 2` (run a standing second replica).** This is the actual, complete fix for zero-downtime deployment — a healthy replica always exists to serve traffic while the other rolls. Explicitly rejected: it doubles Fargate compute cost for a benefit that doesn't apply here — this is a personal dev environment with no real traffic during a deploy, and the project's $0–5/month budget target and single-instance design (`desired_count=1` throughout) predate and constrain this decision. Revisit only if that budget constraint changes.

**Reduce `start_period` to detect the new task as unhealthy faster.** Rejected as unsafe, not just unhelpful: with real cold start (~122s) longer than a short `start_period`, ECS would start counting health-check failures against `retries` while the task is still legitimately starting, likely marking it `UNHEALTHY` and cycling it before it ever finishes booting — combined with `deployment_circuit_breaker.enable=true`, this risks a failed-deployment loop that never successfully ships, which is worse than the flicker it would be trying to prevent.

---

## Consequences

**Positive:**
+ Deployment-time failure rate reduced from guaranteed 100% to roughly 50%, for the same ~2-minute window — a real, measured, substantial improvement, verified by two live deployments before and after
+ Reuses the exact mechanism (`RedisHealthIndicator`/`DbHealthIndicator`, health groups) already built for the `redis-client` PR — no new application code
+ Opt-in per service (`health_check_command = null` default) — doesn't force document-service, ai-assistant, or any future module consumer to have a matching endpoint before they're ready for one
+ Corrects the `liveness`/`readiness` naming to match standard semantics
+ `interval`/`retries` tuning correctly protects against post-deploy runtime degradation, independent of the startup-flicker limitation

**Negative:**
− Does **not** achieve zero-downtime deployment — accepted, not fixed, given the cost constraint (see Alternatives)
− Deployments take visibly longer to reach "stable" — `aws ecs wait services-stable` now honestly reports the full ~2-minute startup instead of the misleadingly fast "stable" it reported before
− `start_period` is a manually-measured constant (150s), not self-tuning — a future increase in real startup time requires revisiting it or ECS will kill legitimately-slow-starting tasks
− `deployment_minimum_healthy_percent=100` means a deployment briefly runs 2 tasks (up to `maximum_percent=200%`) — slightly higher transient compute during the swap itself, though not a standing cost increase

---

## Revisit when

- The remaining ~50%-failure/~2-minute deployment window becomes a real problem (i.e., this environment starts carrying real traffic during deploys) — at that point `desired_count=2` is the actual fix, not further tuning of this mechanism
- Real cold-start time changes meaningfully (measure via the `Started AuthWorkspaceApplication in Ns` log line used to derive the current 150s `start_period`)
- document-service or ai-assistant graduate past their walking-skeleton stage and need the same pattern — apply the same `readiness`-group + `health_check_command` combination, and budget for the same residual limitation, not a fresh investigation
