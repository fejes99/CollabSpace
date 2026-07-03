# ADR-031: ECS Container-Level Health Check for Traffic Readiness

**Status:** Accepted
**Date:** 2026-07-03

---

## Context

Cloud Map (ADR-026) currently tracks task health purely from ECS task state: a task is "healthy" (registered in DNS, eligible for API Gateway VPC Link routing) the instant ECS reports it `RUNNING`, and "unhealthy" only on `STOPPED`/`DRAINING`. This has no visibility into whether the *application* inside the container has actually finished starting.

This gap was not theoretical — it was reproduced live while deploying the `redis-client` PR. auth-workspace's measured cold start (JPA + Flyway migrations + JWT key load from SSM + Tomcat) took **122 seconds**. During a rolling deployment, the new task was reported `RUNNING` (and therefore Cloud Map-eligible) almost immediately, while the old task was simultaneously deregistered — producing a window of roughly two minutes where API Gateway could round-robin to an IP that wasn't actually listening yet. This surfaced as real `503 Service Unavailable` responses from API Gateway itself, not from the application.

The `ecs-service` module's own code comment has flagged this exact gap since it was written: *"Adding a container-level healthCheck block to the task definition (see ADR-026 consequences) improves signal quality here."* This ADR closes that flagged gap.

---

## Decision

Add an optional container-level ECS health check to the `ecs-service` module (`var.health_check_command`, default `null` — opt-in per service, since not every service using the module has an equivalent endpoint yet). For auth-workspace, wire it to:

```
CMD-SHELL curl -f http://localhost:8080/actuator/health/readiness || exit 1
```

`interval=15s`, `timeout=5s`, `retries=3`, `start_period=150s` (comfortably past the measured 122s cold start — a `start_period` shorter than real startup time would cause ECS to kill tasks that are still legitimately starting, which is worse than the problem being solved).

This also corrects a naming mistake from the `redis-client` PR: `db` was placed in the `liveness` group, but semantically it belongs in `readiness`. **Readiness** answers "should traffic be routed here right now" — a DB outage should stop routing, but the container shouldn't be killed and restarted, since restarting never fixes an external Neon outage. **Liveness** answers "should this process be killed and restarted" — reserved for internally-fixable brokenness, and left at Spring Boot's default (no external dependency checks) for exactly that reason. The ECS health check above correctly targets `readiness`, not `liveness`.

ECS's own container health status (`HEALTHY`/`UNHEALTHY`/`UNKNOWN`, visible via `aws ecs describe-tasks`) feeds directly into Cloud Map's existing `health_check_custom_config` — no separate Cloud Map configuration change is needed; this was already the design Cloud Map was built to consume, per the module's original comment.

---

## Alternatives considered

**Do nothing — accept the deployment-time 503 window.** Rejected. This isn't a hypothetical edge case; it's a reproducible ~2-minute outage window on every single deployment, and the fix is a well-established pattern (the same idea as Kubernetes readiness probes, and the same idea this project's own `docker-compose.yml` already applies locally via `depends_on: condition: service_healthy`).

**Route 53 health checks against a public endpoint.** Rejected on cost ($0.75/endpoint/month, explicitly called out as something this design avoids) and unnecessary indirection — the ECS-native container healthCheck accomplishes the same signal quality without an extra AWS resource or public exposure.

**Increase `deployment_minimum_healthy_percent` instead.** Rejected — this controls how many *old* tasks stay up during a deployment, not whether a *new* task is actually ready before Cloud Map considers it eligible. It doesn't address the root cause: Cloud Map has no way to know the new task isn't ready yet, regardless of how many old tasks are kept around.

---

## Consequences

**Positive:**
+ Closes a reproduced, real deployment-time outage window, not a speculative one
+ Reuses the exact mechanism (`RedisHealthIndicator`/`DbHealthIndicator`, health groups) already built for the `redis-client` PR — no new application code
+ Opt-in per service (`health_check_command = null` default) — doesn't force document-service, ai-assistant, or any future module consumer to have a matching endpoint before they're ready for one
+ Corrects the `liveness`/`readiness` naming to match standard semantics, making the distinction meaningful for future services that adopt this pattern

**Negative:**
− Deployments now take visibly longer to reach "stable" from Terraform/CI's perspective — `aws ecs wait services-stable` won't return until the health check passes, meaning it will now honestly report the full ~2-minute startup instead of the misleadingly fast "stable" it reported before. This is surfacing true readiness time, not adding real delay, but it will look like a regression in deploy-time metrics if not understood
− `start_period` is a manually-measured constant (150s), not self-tuning — if a future change meaningfully increases real startup time (e.g., a new slow migration), this value needs to be revisited or ECS will start killing healthy-but-slow-starting tasks
− One more moving part in the task definition JSON; a wrong `startPeriod`/`retries` combination fails loudly (task churn) rather than silently, which is preferable but still needs to be understood by whoever touches this next

---

## Revisit when

- Real cold-start time changes meaningfully (measure via the same `Started AuthWorkspaceApplication in Ns` log line used to derive the current 150s `start_period`)
- document-service or ai-assistant graduate past their walking-skeleton stage and need the same pattern — apply the same `readiness`-group + `health_check_command` combination, not a new one
- ECS/Cloud Map health signal propagation delay (the time between a container health check failing and Cloud Map actually deregistering the task) is measured and found too slow for acceptable failover time
