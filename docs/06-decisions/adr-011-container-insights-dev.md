# ADR-011: Container Insights Disabled in Dev

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

AWS CloudWatch Container Insights is an optional ECS cluster setting that collects per-task metrics: CPU utilisation, memory utilisation, network I/O, and storage I/O. These metrics are emitted as CloudWatch custom metrics every minute.

Custom metrics in CloudWatch are not free. The cost model is $0.30 per metric per month. Container Insights emits approximately 10–15 metrics per running task. With one service and one task:

- ~12 metrics × $0.30/month = **$3.60/month**

CollabSpace targets $0–5/month for all AWS infrastructure in dev. Container Insights alone would consume 70–100% of that budget before accounting for ALB, ECS, or CloudWatch Logs costs.

Container Insights also adds no value in the walking skeleton phase: there is nothing to alert on, no baseline to compare against, and no traffic to observe. Observability investment is better deferred to when a service is actually processing real requests.

---

## Decision

Container Insights is **disabled** in the dev environment (`enable_container_insights = false` in the `ecs-cluster` module).

The setting is exposed as a module variable so staging and prod environments can enable it without modifying the module.

Basic observability in dev is provided by:
- **CloudWatch Logs** — all container stdout/stderr is shipped to the log group (`/collabspace/dev/{service}`). This is free within the 7-day retention window.
- **ECS task stopped reason** — when a task crashes, ECS records why in the console and the API. Sufficient for development debugging.
- **ALB access logs** — not enabled in dev (S3 cost for log storage), but the target group health check status is always visible in the console.

---

## Alternatives Considered

**Enable Container Insights everywhere (standard AWS guidance)**
- Gives a consistent observability baseline across environments.
- Cost in dev: $3.60–5.40/month depending on metric count. Violates the budget constraint.

**Enable Container Insights but sample at a lower rate**
- CloudWatch custom metrics cannot be sampled at the source. The metric emission rate is fixed by AWS at 1-minute intervals. Not configurable.

**Use Prometheus + Grafana instead**
- Better dashboards, no per-metric charge.
- Requires running Prometheus (EC2 or ECS task) and Grafana — adds significant infrastructure complexity.
- Not appropriate for a walking skeleton phase.

---

## Consequences

**Positive:**
- Dev environment stays within the $0–5/month budget.
- Module interface is clean: one boolean variable controls the setting.

**Negative:**
- Per-task CPU and memory metrics are not available in dev. If a task is OOM-killed, you will see the stopped reason in ECS but not a memory utilisation trend.
- Enabling Container Insights in prod after having it off in dev means no historical dev baseline to compare against. This is acceptable since dev is a learning environment, not a traffic-representative replica.

---

## Revisit when

- Moving a service to staging or production — enable Container Insights at that point.
- Debugging a persistent performance issue in dev that cannot be diagnosed from logs alone.
- AWS changes Container Insights pricing (currently $0.30/metric/month).
