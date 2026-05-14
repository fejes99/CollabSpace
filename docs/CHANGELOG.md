# CollabSpace Changelog

Project history. Each completed milestone is recorded here so `CLAUDE.md` Layer 2 can stay focused on the *current* stage rather than carrying the entire past.

New entries go at the top. Each entry names the stage, the date completed, and bullet points the artifacts.

---

## Stage 1 — Walking Skeleton (complete, 2026-05)

All five services deployed to AWS dev environment and reachable via the ALB.

- `auth-workspace` — Spring Boot 4.0.6 + Java 25; `/actuator/health` → 200 OK from ECS Fargate.
- `document-service` — Node.js 24 + TypeScript + Fastify 5; `/documents/health` → 200 OK from ECS Fargate.
- `realtime-service` — Node.js 24 + TypeScript + Fastify 5; `/realtime/health` → 200 OK from ECS Fargate.
- `ai-assistant` — Python 3.14 + FastAPI; `/assistant/health` → 200 OK from ECS Fargate.
- `notification` — Node.js 24 Lambda (ZIP deploy, no Docker — see [ADR-023](06-decisions/adr-023-lambda-zip-deployment.md)); `/notifications/health` → 200 OK via ALB.

CI/CD pipelines per service (`.github/workflows/service-*.yml`): test → build → ECR push (or zip for Lambda) → deploy → wait for stability. Path-filtered so each pipeline only fires on its service's changes.

### Stage 1 infrastructure

- `infrastructure/environments/dev/main.tf` — VPC (`10.0.0.0/16`), 2 public + 2 private subnets across `eu-central-1a/b`, IGW + public route table + S3 gateway endpoint, ALB SG / ECS tasks SG / RDS SG, IAM (shared ECS task execution role + per-service task roles), CloudWatch log groups (7-day retention), ECS cluster with Container Insights disabled (see [ADR-011](06-decisions/adr-011-container-insights-dev.md)), internet-facing ALB.
- Reusable Terraform modules: `modules/vpc/`, `modules/security-groups/`, `modules/iam-ecs/`, `modules/cloudwatch/`, `modules/ecs-cluster/`, `modules/alb/`, `modules/ecs-service/`, `modules/lambda-function/`.
- Service listener rules at priorities: notification 20, ai-assistant 30, realtime 40, document 50. Path prefixes per service.

---

## Stage 0 — Foundation (complete, 2026-04)

- `infrastructure/bootstrap/` — applied to real AWS. S3 state bucket + DynamoDB lock table + billing alarm live. Local state only (see [ADR-006](06-decisions/adr-006-terraform-bootstrap-state.md)).
- `infrastructure/shared/` — 4 ECR repos + GitHub Actions OIDC provider + CI IAM role live. S3 remote state (see [ADR-007](06-decisions/adr-007-github-actions-oidc-auth.md)). `collabspace-ecs-deploy` IAM policy permits CI to register task definitions and update ECS services. `lambda-deploy` IAM policy permits CI to call `lambda:UpdateFunctionCode` and `GetFunction` on dev functions.
- ADRs adr-001 through adr-022 — see [docs/06-decisions/](06-decisions/).

---

## How to read this changelog

- The most recent stage / completion is at the top.
- Each entry is dated to roughly the month of completion.
- ADRs are linked inline where they explain a non-obvious choice; the full ADR list lives in [docs/06-decisions/](06-decisions/).
- For an active session, see `CLAUDE.md` Layer 2 for what's *currently* in progress.
