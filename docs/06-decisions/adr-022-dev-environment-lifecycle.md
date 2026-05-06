---
Status: Accepted
Date: 2026-05-06
---

# ADR-022: Dev Environment Lifecycle — Destroy/Apply Between Sessions

## Context

The dev environment costs roughly $30/month if left running continuously:

| Resource | Monthly cost (always-on) |
|---|---|
| ALB (base charge) | ~$13 |
| ECS Fargate tasks (2 × 256 CPU / 512 MB) | ~$16 |
| VPC, SGs, IAM, CloudWatch | $0 |

CollabSpace is a learning project. The dev environment is only needed for two purposes:

1. **Verifying that a CI/CD deploy reached AWS** — happens a few times per milestone, not continuously.
2. **Integration testing against live AWS services** — relevant from Stage 2 onward when real databases exist.

During active coding sessions — writing service code, refactoring Terraform modules, writing tests — the environment does not need to be running. There is no benefit to paying ~$30/month for an ALB that serves no traffic while code is being written.

A secondary constraint: the ALB charges even when all ECS tasks are scaled to zero. Scaling to zero reduces the Fargate portion (~$16/month) but the ALB charge persists. True $0 cost between sessions requires destroying the environment entirely.

## Decision

**Primary pattern — destroy/apply between sessions:**
Run `terraform destroy` on `infrastructure/environments/dev/` at the end of a session. Run `terraform apply` at the start of a session, only when AWS verification is actually needed. Terraform state is stored in S3 so destroy/apply cycles are safe and reproducible.

**Secondary pattern — ECS scale-to-zero within a session:**
When the environment is already up and the developer wants a temporary pause without tearing everything down (e.g., multiple verifications in one sitting with a break in between), scale all ECS services to desired count 0. This stops Fargate billing immediately. The ALB continues to charge (~$0.022/hour). Resume by scaling back to 1.

Both patterns are wrapped in Makefile targets at the repository root:

```
make dev-plan    — terraform plan (preview without applying)
make dev-up      — terraform apply (interactive; shows plan, prompts confirmation)
make dev-down    — terraform destroy (interactive; prompts confirmation)
make dev-pause   — scale all ECS services to desired count 0
make dev-resume  — scale all ECS services back to desired count 1
make dev-status  — show running/desired counts for all ECS services
```

## Alternatives Considered

**Always-on dev environment** — Rejected. ~$30/month for an idle environment is not justified for a learning project with a $0–5/month target. The ALB alone exceeds the budget.

**ECS scale-to-zero only (no destroy/apply)** — Rejected as primary. Leaves the ALB running at ~$13/month. Acceptable as a within-session convenience (secondary pattern), not as the between-session default.

**AWS EventBridge scheduled scale-to-zero** — Rejected. Coding sessions are not on a predictable schedule, so a fixed-time rule would miss late sessions or need constant overrides. Adds infrastructure complexity for marginal benefit.

**Single EC2 t3.nano running all services in Docker** — Rejected. The learning goal includes ECS Fargate. A cheaper EC2 workaround would create a different environment from production, undermining the infrastructure reproducibility the walking skeleton is meant to establish.

**Compute Savings Plans** — Rejected. Requires a 1 or 3 year commitment. Inappropriate for a project that may pivot architecture. Does not eliminate the ALB base charge.

## Consequences

**Positive:**
- True $0 cost between sessions when the environment is destroyed.
- Regular destroy/apply cycles implicitly verify that infrastructure is reproducible and free of undeclared state drift — a passive test of Terraform configuration quality.
- No new AWS resources required. No EventBridge rules, no scheduled tasks, no additional IAM.
- `make dev-up` makes the cost/benefit tradeoff visible: the developer actively chooses to pay while verifying.

**Negative:**
- `make dev-up` takes 5–10 minutes (terraform apply for VPC, ALB, ECS services). This is a one-time delay per session where AWS verification is needed.
- CI/CD deploy workflows fail if the environment is destroyed. `make dev-up` must be run before pushing a commit intended for deploy verification.
- ECS services get new task ARNs after each destroy/apply. Irrelevant for the walking skeleton; may affect log query history in CloudWatch.
- `make dev-pause` leaves the ALB running (~$13/month). If the developer leaves the environment "paused" for an extended period without destroying it, ALB costs accumulate.

## Revisit When

- Stage 2+ with active feature development requiring more than 3–4 deploy verifications per day. At that frequency the 5–10 minute startup time per session becomes disruptive enough to justify leaving the ALB up and using scale-to-zero as the primary pattern.
- AWS introduces per-request ALB billing or a paused-ALB state without a base hourly charge.
