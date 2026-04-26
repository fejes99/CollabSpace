# Infrastructure

Terraform configuration for CollabSpace, organised into three independent layers applied in order.

## Layers

| Layer | Directory | State | Apply once or repeatedly |
|---|---|---|---|
| Bootstrap | `bootstrap/` | Local (`terraform.tfstate`) | Once — creates the state backend |
| Shared | `shared/` | S3 remote | Once — creates account-wide resources |
| Dev environment | `environments/dev/` | S3 remote | Repeatedly — torn down and recreated between sessions |

Each layer is a self-contained Terraform root module with its own `init`, `plan`, and `apply` cycle. They are not linked by Terraform itself — outputs from one layer are consumed by the next via `terraform_remote_state` data sources, which read the upstream state file directly from S3. See [ADR-008](../docs/06-decisions/adr-008-cross-root-module-state-sharing.md) for the rationale and alternatives considered.

## Prerequisites

- Terraform >= 1.9 (`terraform -version`)
- AWS CLI configured for the target account (`aws sts get-caller-identity`)
- AWS account with billing alerts enabled (required for the bootstrap billing alarm)

## First-time setup (ordered)

### 1. Bootstrap — run once, ever

```bash
cd infrastructure/bootstrap
terraform init
terraform plan
terraform apply
```

Confirm the SNS billing alarm email subscription after apply. See [`bootstrap/README.md`](bootstrap/README.md) for details.

### 2. Shared — run once per AWS account

```bash
cd infrastructure/shared
terraform init
terraform plan
terraform apply
```

Creates ECR repositories and the GitHub Actions OIDC provider. See [`shared/README.md`](shared/README.md) for details.

### 3. Dev environment

```bash
cd infrastructure/environments/dev
terraform init
terraform plan
terraform apply
```

Creates application infrastructure for the dev environment. Currently a skeleton that proves remote state is reachable; VPC, ECS cluster, RDS, and other resources are added in Stage 1. This layer is designed to be destroyed between sessions for cost control. See [`environments/dev/README.md`](environments/dev/README.md) for details.

## Destroying for cost control

Only the dev environment is meant to be torn down regularly:

```bash
cd infrastructure/environments/dev
terraform destroy
```

Do not destroy `shared/` or `bootstrap/`. The shared layer holds ECR images that take time to rebuild. The bootstrap layer holds the state backend — destroying it corrupts state for all other layers.

## Module structure

Reusable modules live in `modules/`. Each module encapsulates one concept and is composed by the environment root modules. Modules are never applied directly.

| Module | What it creates |
|---|---|
| [`modules/vpc/`](modules/vpc/README.md) | VPC, subnets, IGW, route tables, S3 gateway endpoint |
| [`modules/security-groups/`](modules/security-groups/README.md) | ALB, ECS tasks, and RDS security groups with minimal rules |
| [`modules/iam-ecs/`](modules/iam-ecs/README.md) | Shared ECS task execution role, per-service task roles |
| [`modules/cloudwatch/`](modules/cloudwatch/README.md) | Per-service log groups with retention policy |
| [`modules/ecs-cluster/`](modules/ecs-cluster/) | ECS cluster with Container Insights toggle |
| [`modules/alb/`](modules/alb/) | Internet-facing ALB, HTTP listener with fixed-response default |
| [`modules/ecs-service/`](modules/ecs-service/) | Target group, listener rule, task definition, ECS service (generic, one call per service) |
