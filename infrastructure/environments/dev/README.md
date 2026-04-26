# environments/dev

Terraform root module for the CollabSpace **dev environment**.

This is the top of the infrastructure stack for dev. It owns its own state file in S3 and composes reusable modules from `infrastructure/modules/` (added in Stage 1). It reads shared infrastructure outputs (ECR repository URLs, GitHub Actions IAM role) from the `shared/` layer via `terraform_remote_state` — see [ADR-008](../../../docs/06-decisions/adr-008-cross-root-module-state-sharing.md).

## Current state

**Stage 0 skeleton.** No AWS resources are created yet. The sole purpose of this skeleton is to prove the remote state backend is reachable and that the `shared/` layer's outputs can be consumed correctly. Stage 1 adds VPC, subnets, security groups, ECS cluster, and RDS.

## Prerequisites

- Terraform >= 1.9
- AWS CLI authenticated to the CollabSpace account (`aws sts get-caller-identity`)
- `infrastructure/bootstrap/` and `infrastructure/shared/` already applied

## Usage

```bash
cd infrastructure/environments/dev

# First time only — downloads provider, connects to S3 backend
terraform init

# Review what would change (currently no-op: no resources declared)
terraform plan

# Apply (currently no-op)
terraform apply

# Verify remote state is wired correctly — should print ECR URLs and role ARN
terraform output
```

### Expected output after init + apply

```
aws_account_id          = "440808375671"
ecr_repository_urls     = {
  "ai-assistant"      = "440808375671.dkr.ecr.eu-central-1.amazonaws.com/collabspace-ai-assistant"
  "auth-workspace"    = "440808375671.dkr.ecr.eu-central-1.amazonaws.com/collabspace-auth-workspace"
  "document-service"  = "440808375671.dkr.ecr.eu-central-1.amazonaws.com/collabspace-document-service"
  "realtime-service"  = "440808375671.dkr.ecr.eu-central-1.amazonaws.com/collabspace-realtime-service"
}
environment             = "dev"
github_actions_role_arn = "arn:aws:iam::440808375671:role/collabspace-github-actions-ci"
```

If `ecr_repository_urls` or `github_actions_role_arn` are empty or missing, the remote state data source failed to resolve — check that `shared/` has been applied and that the S3 key `shared/terraform.tfstate` exists.

## Cost

Zero. No AWS resources are created by this skeleton. Resources added in Stage 1 are designed to stay within free-tier limits during active sessions and are destroyed between sessions.

## Destroy

```bash
terraform destroy
```

Safe to run at any time. Only resources declared in this module are destroyed. The `shared/` layer (ECR repos, OIDC provider) and `bootstrap/` layer (S3 bucket, DynamoDB table) are not affected.

## State

| Key | Value |
|---|---|
| Backend | S3 |
| Bucket | `collabspace-terraform-state-440808375671` |
| Key | `environments/dev/terraform.tfstate` |
| Lock table | `collabspace-terraform-locks` |

## What comes next (Stage 1)

- VPC with public/private subnets across two AZs
- Security groups per service
- ECS cluster (Fargate)
- RDS PostgreSQL instance (auth-workspace)
- Upstash Redis (provisioned outside Terraform — see architecture docs)
