# Infrastructure

Terraform configuration for CollabSpace, organised into three independent layers applied in order.

## Layers

| Layer | Directory | State | Apply once or repeatedly |
|---|---|---|---|
| Bootstrap | `bootstrap/` | Local (`terraform.tfstate`) | Once — creates the state backend |
| Shared | `shared/` | S3 remote | Once — creates account-wide resources |
| Dev environment | `environments/dev/` | S3 remote | Repeatedly — torn down and recreated each session |

Each layer is a self-contained Terraform root module with its own `init`, `plan`, and `apply` cycle. They are not linked by Terraform itself — outputs from one layer are consumed by the next by reading them manually or via `terraform_remote_state`.

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

Creates application infrastructure (VPC, ECS cluster, RDS, etc). This layer is designed to be destroyed at the end of a session and recreated at the start of the next. See `environments/dev/README.md` for details.

## Destroying for cost control

Only the dev environment is meant to be torn down regularly:

```bash
cd infrastructure/environments/dev
terraform destroy
```

Do not destroy `shared/` or `bootstrap/`. The shared layer holds ECR images that take time to rebuild. The bootstrap layer holds the state backend — destroying it corrupts state for all other layers.

## Module structure

Reusable modules live in `modules/`. Each module encapsulates one concept (e.g. an ECS service, a security group pattern) and is composed by the environment configs. Modules are not applied directly.
