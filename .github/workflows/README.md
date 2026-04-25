# GitHub Actions Workflows

Workflow YAML files will be added here as each service and infrastructure
component is built. This file documents the intended pipeline structure.

All workflows use OIDC authentication — no long-lived AWS credentials are
stored as secrets. See `docs/05-cicd/` for the full pipeline design.

---

## Planned workflows

### Service CI (per-service builds, tests, and deploys)

| File | Purpose |
|------|---------|
| `ci-service-template.yml` | Reusable workflow (called by the per-service workflows below). Handles lint, test, Docker build, ECR push, and ECS deploy. |
| `service-auth.yml` | CI/CD for `services/auth-workspace` (Java + Spring Boot). |
| `service-document.yml` | CI/CD for `services/document-service` (TypeScript + Express). |
| `service-realtime.yml` | CI/CD for `services/realtime-service` (TypeScript + ws). |
| `service-ai.yml` | CI/CD for `services/ai-assistant` (Python + FastAPI). |

### Lambda

| File | Purpose |
|------|---------|
| `lambda-notification.yml` | Package and deploy `services/notification` to AWS Lambda. Triggered on push to `main` when `services/notification/**` changes. |

### Infrastructure

| File | Purpose |
|------|---------|
| `infra-plan.yml` | Run `terraform plan` on PRs that touch `infrastructure/**`. Posts the plan as a PR comment. |
| `infra-apply.yml` | Run `terraform apply` on merge to `main` for `infrastructure/**` changes. Requires explicit approval via GitHub Environment protection rule. |
| `infra-destroy.yml` | Manually triggered (`workflow_dispatch` only). Tears down the dev environment. Requires approval. |

---

## Trigger strategy (summary)

- Per-service workflows trigger on path filters (`services/<name>/**`) to
  avoid unnecessary builds across the monorepo.
- Infrastructure workflows trigger on `infrastructure/**` path filters.
- `infra-apply.yml` and `infra-destroy.yml` are gated by a GitHub Environment
  (`dev`) with a required reviewer — no unattended applies.
- All workflows assume an OIDC-federated IAM role; the role ARN will be stored
  as a repository secret (`AWS_ROLE_ARN`), not a key/secret pair.
