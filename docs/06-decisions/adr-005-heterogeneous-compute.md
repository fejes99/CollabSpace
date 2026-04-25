# ADR-005: Heterogeneous Compute — Fargate, EC2, and Lambda

**Status:** Accepted  
**Date:** 2026-04-25

---

## Context

CollabSpace runs five services with meaningfully different runtime
characteristics: stateless containerised APIs, a persistent WebSocket
server, a stateful message broker, and a stateless event-driven function.
Mapping all five to a single compute model would require accepting a poor
fit on at least two of them.

The decision was made service-by-service, with two constraints applied
consistently: stay within or near the AWS free tier ($0–5/month target),
and prefer the compute model that makes the runtime characteristics of each
service explicit rather than hidden.

A sixth compute decision — where to host the two PostgreSQL databases
(Auth & Workspace and AI Assistant) — is covered in ADR-006.

---

## Decision

### ECS Fargate — Auth & Workspace, Document Service, AI Assistant

These three services are stateless, containerised, and have no runtime
dependency on a stable host identity. Fargate is the right model:

- **No instance management.** No AMI selection, OS patching, or SSH
  access required. The task definition is the full infrastructure spec.
- **Scale-to-zero with teardown discipline.** Fargate has no idle cost
  when tasks are stopped. Combined with session-boundary teardown (see
  [cost-strategy.md](../04-infrastructure/cost-strategy.md)), the free
  tier allocation (50 vCPU-hours + 100 GB RAM-hours per month, 12-month)
  covers active development sessions without persistent spend.
- **Pay-per-second billing.** Fargate bills only for running task time.
  A two-hour development session across three services costs cents, not
  dollars.
- **Config-as-code.** ECS task definitions capture CPU, memory, image,
  environment variables, and secrets references in a versionable artifact.
  No configuration drift from manual instance changes.

Fargate's free tier is shared across all three services. Running all three
simultaneously exhausts the monthly allocation in under an hour. Teardown
discipline is therefore non-optional for staying within budget.

### EC2 t3.micro — Realtime Service and Kafka Broker

These two workloads require EC2 for different reasons.

**Realtime Service (WebSocket server):** Persistent WebSocket connections
require a stable compute host. EC2 provides predictable host lifecycle —
the scheduler does not replace the instance during active connections.
Additional advantages: direct SSH access for debugging WebSocket state and
connection counts, lower cost for long-running idle connections (Fargate
bills per vCPU-second; a WebSocket server holding open connections
continuously incurs that cost regardless of message volume), and
educational visibility into kernel-level networking behaviour.

**Kafka broker:** Kafka is a stateful process — topic data, consumer group
offsets, and partition logs are stored on disk. Fargate task replacement
would lose this state. EC2 allows stop (not terminate) between sessions:
EBS-backed stopped instances preserve all Kafka data on the attached
volume, incurring only EBS storage cost (~$0.10/GB/month) rather than
compute cost.

Both instances share the EC2 free tier (750 instance-hours/month, 12-month).
Two t3.micro instances running simultaneously consume ~1,440 hours/month
against the 750-hour allocation — exceeding it by ~$8/month if left
running. Stop both instances between development sessions.

### Lambda (Node.js 22) — Notification Service

The Notification Service is stateless, event-driven, and infrequent. It
has no reason to be a persistent process.

Lambda is the correct model: triggered by SQS, executes, exits. No idle
cost, no instance to manage, native SQS trigger integration with built-in
retry and dead-letter queue support. Lambda's free tier (1 million
invocations/month + 400,000 GB-seconds compute) is permanent — not
12-month — and will never be approached at the notification volumes this
project generates. Operating Lambda for a stateless event-driven function
is also a stated learning goal.

### RDS db.t3.micro — PostgreSQL (shared instance)

Covered in ADR-006. Summary: single RDS instance hosts both `auth_db`
(Auth & Workspace) and `vector_db` (AI Assistant) as separate databases
with separate users and least-privilege grants. Cost optimisation: avoids
a second ~$15/month RDS instance.

---

## Compute Summary

| Service | Compute | Primary Reason |
|---|---|---|
| Auth & Workspace | ECS Fargate | Stateless, containerised, scale-to-zero |
| Document Service | ECS Fargate | Stateless, containerised, scale-to-zero |
| AI Assistant | ECS Fargate | Stateless, containerised, scale-to-zero |
| Realtime Service | EC2 t3.micro | Stable host for persistent WebSocket connections |
| Kafka broker | EC2 t3.micro | Stateful; EBS-backed stop preserves broker data |
| Notification | Lambda | Stateless, event-driven, permanent free tier |
| PostgreSQL | RDS db.t3.micro | Managed, 750-hour free tier, shared (see ADR-006) |

---

## Alternatives Considered

### All services on EC2

Simplest operational model: one compute type, one set of runbooks.

Rejected because: three stateless services (Auth, Document, AI) have no
runtime need for a persistent host. Putting them on EC2 adds AMI management
and manual patching for no benefit. EC2 also has no scale-to-zero; idle
instances accrue cost. With three additional instances, the shared 750-hour
free tier would be exhausted in days.

### All services on ECS Fargate

Operationally clean: one container orchestrator, one deployment model.

Rejected for two services: the Realtime Service's WebSocket stability
requirement is better served by EC2's predictable host lifecycle, and Kafka
on Fargate would lose broker state on task replacement. Forcing both onto
Fargate would require workarounds (EFS for Kafka state, connection-draining
tuning for WebSocket) that add complexity without simplifying operations.

### AWS App Runner / Elastic Beanstalk

Higher-abstraction managed platforms.

Rejected because: both abstract away the infrastructure layer that is
part of what this project is learning. ECS Fargate with explicit task
definitions provides the right balance of managed infrastructure and
visible configuration.

---

## Consequences

**Positive:**
+ Each service runs on the compute model that matches its runtime
  characteristics — no forced fit
+ Lambda and SNS/SQS free tiers are permanent; the notification path has
  zero ongoing cost
+ Fargate task definitions are config-as-code; no configuration drift
+ EC2 stop/start preserves Kafka state without full teardown
+ Operating three distinct compute models (Fargate, EC2, Lambda) is a
  learning goal, fulfilled

**Negative:**
− Three deployment models mean three operational runbooks, three sets of
  monitoring, three CI/CD pipeline patterns
− Fargate scale-to-zero is only cost-effective with teardown discipline;
  tasks left running accrue cost faster than equivalent EC2 instances at
  low utilisation
− Two EC2 instances exceed the 750-hour free tier if left running
  continuously — stop discipline is required
− The 12-month free tier on Fargate and EC2 has an expiry; cost profile
  changes materially after the first year

---

## Revisit When

- Free tier windows expire (12-month): re-evaluate whether always-on EC2
  is cheaper than Fargate for the Realtime Service at that point
- Realtime Service connection count grows to the point where a single EC2
  instance is insufficient — horizontal scaling requires either a managed
  WebSocket service or ECS with connection draining tuning
- Kafka operational overhead (patching, monitoring, KRaft quorum) exceeds
  the learning value — consider MSK Serverless if pricing becomes
  free-tier-compatible
- AI Assistant pgvector queries create measurable latency impact on Auth
  (RDS co-location revisit — see ADR-006)
