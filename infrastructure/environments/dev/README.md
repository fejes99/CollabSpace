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
| `auth_workspace` | Target group, listener rule, task definition, ECS service for auth-workspace | Image placeholder `:skeleton` — push to ECR to activate |

**Not created here:**
- RDS instances (added when auth-workspace service is built)
- Upstash Redis (provisioned outside Terraform — external SaaS)
- MongoDB Atlas (provisioned outside Terraform — external SaaS)

## Prerequisites

- Terraform >= 1.9
- AWS CLI authenticated to the CollabSpace account (`aws sts get-caller-identity`)
- `infrastructure/bootstrap/` and `infrastructure/shared/` already applied

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
| ECS Fargate task (256 CPU / 512 MB, 1 task) | ~$0.011/hour (~$8/month) |
| Container Insights | Disabled — $0 (see ADR-011) |

**Estimated total: ~$1–2/day when running.** Destroy the environment between sessions to stay within budget.

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

## What comes next (Stage 1 continued)

- Build `auth-workspace` Spring Boot container returning 200 OK on `/actuator/health`
- Push image to ECR with the `:skeleton` tag (unlocks the ECS service)
- GitHub Actions workflow that builds and deploys on push to main
