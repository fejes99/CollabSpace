# environments/dev

Terraform root module for the CollabSpace **dev environment**.

Composes reusable modules from `infrastructure/modules/` and reads account-wide outputs (ECR URLs, GitHub Actions IAM role) from the `shared/` layer via `terraform_remote_state`. See [ADR-008](../../../docs/06-decisions/adr-008-cross-root-module-state-sharing.md).

## What it creates

| Module | Resources | Notes |
|---|---|---|
| `vpc` | VPC, IGW, 2 public subnets, 2 private subnets, route table, S3 gateway endpoint | ECS tasks in public subnets — see ADR-009 |
| `security_groups` | ALB SG, ECS tasks SG, RDS SG + rules | No Redis SG — Upstash is external SaaS |
| `iam_ecs` | Shared task execution role, 4 per-service task roles, SSM read policy | Notification uses Lambda execution role, not ECS |
| `cloudwatch` | 5 log groups (`/collabspace/dev/{service}`), 7-day retention | Includes notification Lambda |
| `ecs_cluster` | ECS cluster | Container Insights disabled in dev — see ADR-011 |
| `alb` | Internet-facing ALB, HTTP listener (default: 404) | Services attach their own listener rules |
| `auth_workspace` | Target group, listener rule, task definition, ECS service for auth-workspace | Healthy — `/actuator/health` returns 200 OK; CI/CD manages image updates via `service-auth.yml` |
| `document_service` | Target group, listener rule, task definition, ECS service for document-service | Walking skeleton — `/health` returns 200 OK; path prefix `/documents/*` at priority 50; CI/CD via `service-document.yml` |
| `realtime_service` | Target group, listener rule, task definition, ECS service for realtime-service | Walking skeleton — `/health` returns 200 OK; path prefix `/realtime/*` at priority 40; CI/CD via `service-realtime.yml` |
| `ai_assistant` | Target group, listener rule, task definition, ECS service for ai-assistant | Walking skeleton — `/health` returns 200 OK; path prefix `/assistant/*` at priority 30; CI/CD via `service-ai.yml` |
| `notification` | Lambda function, execution role, ALB target group + listener rule | Walking skeleton — `/notifications/health` returns 200 OK; path prefix `/notifications/*` at priority 20; CI/CD via `service-notification.yml` (ZIP deploy, no ECR — see ADR-023) |

**Not created here:**
- RDS instances (added when auth-workspace service is built)
- Upstash Redis (provisioned outside Terraform — external SaaS)
- MongoDB Atlas (provisioned outside Terraform — external SaaS)

## Prerequisites

- Terraform >= 1.9
- AWS CLI authenticated to the CollabSpace account (`aws sts get-caller-identity`)
- `infrastructure/bootstrap/` and `infrastructure/shared/` already applied

## Lifecycle

The dev environment is brought up on demand and destroyed between sessions to keep costs at $0 when not in use. See [ADR-022](../../../docs/06-decisions/adr-022-dev-environment-lifecycle.md).

Run these from the **repository root** (not this directory):

| Target | What it does | When to use |
|---|---|---|
| `make dev-plan` | `terraform plan` — previews changes without applying | Before any apply |
| `make dev-up` | `terraform apply` — full environment up (5–10 min) | Start of a verification session |
| `make dev-down` | `terraform destroy` — tears everything down to $0 | End of session |
| `make dev-pause` | Scales all ECS tasks to 0 — stops Fargate billing | Within-session pause; ALB still runs |
| `make dev-resume` | Scales all ECS tasks back to 1 | Resume after a pause |
| `make dev-status` | Shows running/desired task counts | Verify state at any time |

**Cost when up:** ~$1–2/day (ALB + Fargate). **Cost when destroyed:** $0.

> CI/CD deploy workflows fail when the environment is destroyed — run `make dev-up` before pushing a commit that triggers an ECS deploy.

## Usage

All commands run from this directory:

```bash
cd infrastructure/environments/dev
```

**First time only** — downloads provider, resolves module sources, connects to S3 backend:

```bash
terraform init
```

**Review changes before applying:**

```bash
terraform plan
```

**Apply to AWS:**

```bash
terraform apply
```

**Inspect what was created:**

```bash
terraform output
```

## Network layout

```
VPC 10.0.0.0/16
├── Public subnet eu-central-1a  10.0.1.0/24   ← ALB, ECS tasks
├── Public subnet eu-central-1b  10.0.2.0/24   ← ALB, ECS tasks
├── Private subnet eu-central-1a 10.0.11.0/24  ← RDS (when provisioned)
└── Private subnet eu-central-1b 10.0.12.0/24  ← RDS (when provisioned)
```

Two AZs in dev. See [ADR-010](../../../docs/06-decisions/adr-010-two-az-dev-environment.md).

## Cost

Designed to stay within the AWS free tier for active development:

| Resource | Cost |
|---|---|
| VPC, subnets, IGW, route tables | Free |
| S3 gateway endpoint | Free |
| Security groups | Free |
| IAM roles and policies | Free |
| CloudWatch log groups (7-day retention, low volume) | Free tier |
| ECS cluster | Free |
| ALB | ~$0.022/hour (~$16/month) + $0.008/LCU — main non-free cost in dev |
| ECS Fargate tasks (256 CPU / 512 MB, 4 tasks) | ~$0.044/hour (~$32/month) |
| Container Insights | Disabled — $0 (see ADR-011) |

**Estimated total: ~$1–4/day when running.** Destroy the environment between sessions to stay within budget.

No NAT Gateway (that alone would be ~$32/month). See [ADR-009](../../../docs/06-decisions/adr-009-ecs-public-subnet-strategy.md).

## State

| Key | Value |
|---|---|
| Backend | S3 |
| Bucket | `collabspace-terraform-state-440808375671` |
| Key | `environments/dev/terraform.tfstate` |
| Lock table | `collabspace-terraform-locks` |

## Destroy

```bash
terraform destroy
```

Safe to run between sessions for cost control. Only resources in this module are destroyed. The `shared/` and `bootstrap/` layers are not affected.

## What comes next

Walking skeleton complete — all five services deployed. Stage 2 work:

- Wire the routing layer: API Gateway routes, ALB listener rule for WebSocket, SNS → SQS → Lambda subscription
- Provision data stores: RDS (auth-workspace), MongoDB Atlas (document-service), pgvector (ai-assistant)
- Enable Container Insights for ECS services (disabled in dev — ADR-011)
