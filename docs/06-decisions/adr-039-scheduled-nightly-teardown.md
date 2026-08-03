# ADR-039: Scheduled Nightly Teardown of the Dev Environment

**Status:** Accepted
**Date:** 2026-08-03

---

## Context

July's AWS bill was $35.27 — traced via Cost Explorer to two dev-environment sessions that ran unattended for far longer than a coding session: ~10 days (Jul 3–13) and ~5 days (Jul 16–21). CloudTrail confirms `ecs:CreateService` at the start of each window and `ecs:DeleteService`/`DeleteCluster` at the end, with nothing in between — `make dev-down` simply wasn't run at the end of those two sessions.

Breakdown of the $35.27:

| Item | Cost | Cause |
|---|---|---|
| Fargate compute (367.3 vCPU-hrs) | $20.86 | 4 services × 0.25 vCPU running the full unattended window |
| Public IPv4 (1470 IP-hours) | $7.35 | One IP per ECS task ENI, same window |
| Route 53 (2 hosted-zone-months) | $1.00 | `aws_service_discovery_private_dns_namespace.main` ([main.tf:230](../../infrastructure/environments/dev/main.tf#L230)) provisions a private hosted zone; AWS waives the $0.50 charge only if a zone is deleted within 12 hours of creation. Both long sessions blew past that window; the two short sessions that day didn't. |
| Tax | $5.88 | — |
| ECR storage | $0.18 | Persists regardless (shared-layer resource, unaffected by dev up/down) |

[ADR-022](adr-022-dev-environment-lifecycle.md) already established destroy/apply between sessions as the primary cost-control pattern and considered EventBridge-triggered automation — but only for the *secondary* scale-to-zero pattern, rejecting it as "coding sessions are not on a predictable schedule... adds infrastructure complexity for marginal benefit." It never evaluated automating the *primary* destroy pattern itself.

Two things have changed since ADR-022 was written that reopen this:

1. `cost-strategy.md`'s Option 2 replaced the ALB with API Gateway, removing the ~$13/month persistent base charge that was the main reason full destroy (not scale-to-zero) had to stay the primary lever, rather than something automatable on a schedule.
2. This incident is direct evidence that manual session-end discipline fails in practice, not just in theory.

Scale-to-zero was re-evaluated as the automation target instead of full destroy, and rejected: the service-discovery private hosted zone above is a **VPC-level resource**, not a **task-level** one — scaling ECS desired count to 0 doesn't touch it. Under permanent scale-to-zero it would never see a 12-hour deletion window again, guaranteeing a ~$0.50/month floor forever. That's a worse steady state than a correctly-automated destroy.

## Decision

Add a scheduled, unconditional `terraform destroy` of `infrastructure/environments/dev/` (not `infrastructure/shared/`) at 23:59 Europe/Berlin every day, run via **EventBridge Scheduler invoking a dedicated CodeBuild project**. This is a backstop for exactly the failure mode above, not a replacement for `make dev-down` at session end — the manual habit stays the primary pattern; this catches the case where it's forgotten.

If a session is still active at 23:59, the destroy proceeds anyway. Recovery is `make dev-up` (5–10 min). This is acceptable because Neon (Postgres) and MongoDB Atlas hold all real state outside `environments/dev` — nothing torn down nightly is irreplaceable.

Two implementation constraints, both surfaced by stress-testing this decision before writing it up:

- **The CodeBuild buildspec must be defined inline in the `aws_codebuild_project` Terraform resource, not fetched from the GitHub repo at build time.** The IAM role this job assumes can delete VPCs, IAM roles, and API Gateway resources — full destroy permissions for the dev environment. If the buildspec were sourced from the repo, any change merged to the branch CodeBuild reads from could silently redirect what that privileged role does on its next scheduled run, with no `terraform plan` in front of it. Keeping the buildspec in Terraform means changing the nightly job's behavior requires a Terraform change — which goes through the same plan-review gate as everything else.
- **The CodeBuild IAM role is scoped to `environments/dev` resources only**, mirroring the existing `github-actions-ci` role's scoping pattern in `infrastructure/shared/oidc.tf` — narrow by resource, not just by service.

## Alternatives considered

**EventBridge scheduled scale-to-zero** (ADR-022's originally-rejected option, re-evaluated here). Rejected again, for a different reason than before: it doesn't reach $0. The service-discovery hosted zone persists indefinitely under scale-to-zero, so its floor (~$0.50–0.70/month) is worse in steady state than a working destroy cycle.

**GitHub Actions `schedule:` cron, reusing the existing CI OIDC role.** Rejected. Would require widening `github-actions-ci`'s policy — currently scoped to ECR push, `ecs:UpdateService` on dev, and Lambda code update — to full-account destroy permissions. That role is already flagged in its own code comment as broader than ideal (assumable from any branch, not just `main`). GitHub's cron scheduler is also documented to skip or delay runs under platform load and disables itself after 60 days without a push to the repo — a bad property for a cost-control backstop that needs to fire reliably regardless of how active the repo is.

**Local cron / launchd on the developer's laptop.** Rejected. Only fires if the laptop is awake and unlocked at 23:59. The failure mode this ADR addresses is sessions left unattended — a mechanism that depends on the laptop being attended defeats its own purpose.

**Do nothing beyond the new $5 AWS Budgets alarm.** Rejected as insufficient alone. The alarm notifies after spend has already happened; it doesn't cap how long an environment runs unattended. Kept as a second line of defense, not a substitute for this.

## Implementation note (2026-08-03)

The first real test run exposed a bug this ADR's alternatives analysis didn't catch: putting `module.scheduled_teardown` inside `infrastructure/environments/dev`'s own Terraform state means a successful destroy also destroys the CodeBuild project and EventBridge schedule that were supposed to run again the next night. It fired exactly once and deleted itself — the opposite of a recurring backstop.

Fix: the module lives in `infrastructure/shared/` instead — the state that's never destroyed — and still targets `environments/dev` as its destroy working directory. This also let several self-referential IAM grants added mid-debugging (the destroy role needing permission to read/delete its own CodeBuild project and schedule) be removed again as dead weight, since the module no longer needs to destroy itself.

Caught by the "test manually before trusting the schedule" step this ADR's own Decision section called for — not by the nightly schedule itself, which would have silently failed to recur with no one watching.

## Consequences

**Positive:**
+ Caps the blast radius of a forgotten teardown to at most ~24 hours, down from the ~10 days observed in July.
+ Destroy permissions are isolated to a CodeBuild-specific IAM role, never exposed to the GitHub Actions CI/deploy path — the deploy security boundary is untouched.
+ Free at this scale: CodeBuild's always-free tier (100 build-minutes/month) easily covers a nightly few-minute destroy job, including nights where it's a no-op against an already-empty state.
+ No dependency on the developer's laptop or GitHub Actions being available — pure AWS-native scheduling, immune to both of the failure modes the rejected alternatives have.

**Negative:**
− A session still active at 23:59 gets torn down mid-work; recovery costs a 5–10 minute `terraform apply`. Accepted per Decision — the alternative (an unbounded unattended run) already cost $35 once.
− Adds new infrastructure (CodeBuild project, EventBridge Scheduler rule, a dedicated IAM role) whose only purpose is compensating for a manual step not always happening. That's real complexity spent on discipline-enforcement, not a feature.
− A destroy that collides with a manual `make dev-up`/`dev-down` running at the same moment will contend on the same S3/DynamoDB state lock. Terraform's locking prevents corruption, but a stuck lock (e.g., from an earlier interrupted run — as already happened once this session, see the `^C` on a `terraform destroy` that failed on a provider-plugin error) requires a manual `terraform force-unlock` before either side can proceed.
− The nightly job destroys unconditionally at a fixed time — it has no way to distinguish "environment sitting idle" from "environment mid-way through a long-running integration test." A session that happens to be mid-test at 23:59 loses it.
− A dormant, highly-privileged IAM role (full destroy rights over `environments/dev`) now exists and is invoked automatically on a timer rather than only when a human runs `make dev-down`. The inline-buildspec constraint above limits how it can be redirected, but the role's existence is itself a larger standing capability than the project had before.

## Revisit when

- The 23:59 fixed time repeatedly disrupts sessions that run late — consider a per-session opt-out (e.g., an SSM flag or resource tag the CodeBuild job checks before destroying) rather than removing the automation.
- A second developer joins and session timing stops being predictable enough for one fixed local-time trigger to make sense.
- Three or more partial-destroy failures occur (state lock contention, transient AWS API errors) — the CodeBuild job likely needs a lock-check step and a failure notification, not just a bare `terraform destroy`.
- AWS ships a native TTL / scheduled-destroy primitive for Terraform-managed environments, making this custom EventBridge/CodeBuild plumbing redundant.
