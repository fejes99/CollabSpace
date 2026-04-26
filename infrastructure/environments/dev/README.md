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

**Not created here:**
- ECS cluster and services (next step in Stage 1)
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

- ECS cluster
- ALB with target group and listener
- ECS service for `auth-workspace` with a working container
- GitHub Actions workflow that builds and deploys on push
