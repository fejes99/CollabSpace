# Terraform Shared

Account-wide resources shared across all environments: ECR repositories for container images and the GitHub Actions OIDC identity provider.

## What it creates

| Resource | Name | Purpose |
|---|---|---|
| ECR repository × 4 | `collabspace-{service}` | Stores Docker images for each containerised service |
| OIDC identity provider | `token.actions.githubusercontent.com` | Trusts GitHub as an identity provider for CI credentials |
| IAM role | `collabspace-github-actions-ci` | Assumed by GitHub Actions workflows via OIDC |
| IAM policy | `collabspace-ecr-push` | Grants the CI role permission to push images to the four ECR repos |

ECR repositories created:

| Service | Repository name |
|---|---|
| Auth & Workspace | `collabspace-auth-workspace` |
| Document Service | `collabspace-document-service` |
| Realtime Service | `collabspace-realtime-service` |
| AI Assistant | `collabspace-ai-assistant` |

The Notification service (Lambda) uses zip deployment and has no ECR repository.

## Prerequisites

- Bootstrap layer applied (`infrastructure/bootstrap/` — see its README)
- Terraform >= 1.9
- AWS CLI configured

## First-time setup

```bash
cd infrastructure/shared

terraform init

terraform plan

terraform apply
```

## Outputs

```bash
terraform output
```

| Output | Used in |
|---|---|
| `ecr_repository_urls` | CI/CD workflow (image push destination); `environments/dev` via `terraform_remote_state` (ECS task definitions in Stage 1) |
| `github_actions_role_arn` | CI/CD workflow — `role-to-assume` in `configure-aws-credentials`; `environments/dev` via `terraform_remote_state` |
| `oidc_provider_arn` | Reference only — registered once per AWS account |

## Adding a new service

Add the service name to the `services` set in `ecr.tf`:

```hcl
locals {
  services = toset([
    "auth-workspace",
    "document-service",
    "realtime-service",
    "ai-assistant",
    "your-new-service",   # add here
  ])
}
```

Then `terraform plan` and `terraform apply`. One new ECR repository will be created with the same lifecycle policy as the rest.

## Image lifecycle

Each repository retains the last 10 images (configurable via `ecr_max_image_count` in `terraform.tfvars`). Older images are expired automatically. Tags are immutable — once an image is pushed with a tag it cannot be overwritten.

## Destroying

Do not run `terraform destroy` on this layer during normal operation. ECR repositories have `lifecycle { prevent_destroy = true }` set. Destroying the repositories deletes all pushed images permanently.

If you genuinely need to destroy (e.g. starting fresh), remove the `prevent_destroy` lifecycle block from `ecr.tf`, apply, then run `terraform destroy`.
