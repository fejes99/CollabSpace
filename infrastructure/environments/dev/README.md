# environments/dev

Terraform root module for the CollabSpace **dev environment**.

Composes reusable modules from `infrastructure/modules/` and reads account-wide outputs (ECR URLs, GitHub Actions IAM role) from the `shared/` layer via `terraform_remote_state`. See [ADR-008](../../../docs/06-decisions/adr-008-cross-root-module-state-sharing.md).

## What it creates

| Module / Resource | What it creates | Notes |
|---|---|---|
| `vpc` | VPC, IGW, 2 public + 2 private subnets, route tables, S3 gateway endpoint | ECS tasks in public subnets — ADR-009 |
| `security_groups` | VPC Link SG, ECS tasks SG + rules | No ALB SG — REST entry point is API Gateway (ADR-026). ALB SG retained for future realtime-service WebSocket ALB |
| `iam_ecs` | Shared task execution role, 4 per-service task roles, SSM read policy | Notification uses Lambda execution role, not ECS |
| `cloudwatch` | 5 ECS log groups (`/collabspace/dev/{service}`), API Gateway access log group, 7-day retention | |
| `ecs_cluster` | ECS cluster | Container Insights disabled — ADR-011 |
| `api_gateway` | HTTP API, VPC Link, default stage (`$default`), access logs | REST entry point for all services — ADR-026 |
| `aws_apigatewayv2_authorizer.jwt` | JWT Authorizer (RS256) | Defined outside the module to avoid a cold-start bootstrapping problem — ADR-029 |
| `aws_service_discovery_private_dns_namespace` | `collabspace.local` Cloud Map namespace | VPC Link resolves live task IPs via Cloud Map service discovery |
| `auth_workspace` | Task definition, ECS service for auth-workspace | Image resolved from latest ECR push via `aws_ecr_image` data source; CI/CD manages updates via `service-auth.yml` |
| `document_service` | Task definition, ECS service for document-service | Walking skeleton (`:skeleton` image); CI/CD via `service-document.yml` |
| `realtime_service` | Task definition, ECS service for realtime-service | Walking skeleton (`:skeleton` image); CI/CD via `service-realtime.yml` |
| `ai_assistant` | Task definition, ECS service for ai-assistant | Walking skeleton (`:skeleton` image); CI/CD via `service-ai.yml` |
| `notification` | Lambda function, execution role, API Gateway integration | Walking skeleton; ZIP deploy — ADR-023 |
| SSM parameters | DB credentials, JWT keys/issuer/audience, internal token, JWKS URI | Read by ECS tasks at startup via `JWT_*_SSM_PATH` env vars |

**Not created here:**
- Upstash Redis (provisioned outside Terraform — external SaaS)
- MongoDB Atlas (provisioned outside Terraform — external SaaS)
- Neon Postgres (provisioned outside Terraform — external SaaS; connection string in SSM)

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
| `make dev-pause` | Scales all ECS tasks to 0 — stops Fargate billing | Within-session pause |
| `make dev-resume` | Scales all ECS tasks back to 1 | Resume after a pause |
| `make dev-status` | Shows running/desired task counts | Verify state at any time |

**Cost when up:** ~$0.50–1/day (Fargate only — API Gateway HTTP API has no hourly charge). **Cost when destroyed:** $0.

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
├── Public subnet eu-central-1a  10.0.1.0/24   ← ECS tasks, VPC Link ENIs
├── Public subnet eu-central-1b  10.0.2.0/24   ← ECS tasks, VPC Link ENIs
├── Private subnet eu-central-1a 10.0.11.0/24  ← reserved (future use)
└── Private subnet eu-central-1b 10.0.12.0/24  ← reserved (future use)
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
| API Gateway HTTP API | ~$0 at dev scale (no hourly charge; $1/million requests) |
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

API Gateway routing layer is live. Stage 2 service implementation work:

- auth-workspace: login endpoint (`POST /v1/auth/login`), workspace endpoints
- document-service: real implementation (Kotlin + Ktor)
- realtime-service: real implementation (Node.js + WebSocket ALB)
- ai-assistant: real implementation (Python + FastAPI)
- Add container `healthCheck` blocks to ECS task definitions (follow-up from ADR-026)
