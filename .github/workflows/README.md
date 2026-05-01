# GitHub Actions Workflows

Workflow YAML files will be added here as each service and infrastructure
component is built. This file documents the intended pipeline structure.

All workflows use OIDC authentication — no long-lived AWS credentials are
stored as secrets. See `docs/05-cicd/` for the full pipeline design.

---

## Active workflows

| File | Status | Purpose |
|---|---|---|
| `aws-oidc-smoke-test.yml` | Live | Validates OIDC trust chain, STS identity, and scoped ECR access. Runs on push to `infrastructure/shared/**` or manually via `workflow_dispatch`. |

---

## Planned workflows

### Service CI (per-service builds, tests, and deploys)

| File | Status | Purpose |
|------|--------|---------|
| `service-auth.yml` | Next up | CI/CD for `services/auth-workspace` (Java + Spring Boot). Lint, test, Docker build, ECR push, ECS deploy. |
| `ci-service-template.yml` | Planned | Reusable workflow called by per-service workflows above. Extracts shared steps once two or more service workflows exist. |
| `service-document.yml` | Planned | CI/CD for `services/document-service` (TypeScript + Express). |
| `service-realtime.yml` | Planned | CI/CD for `services/realtime-service` (TypeScript + ws). |
| `service-ai.yml` | Planned | CI/CD for `services/ai-assistant` (Python + FastAPI). |

### Lambda

| File | Status | Purpose |
|------|--------|---------|
| `lambda-notification.yml` | Planned | Package and deploy `services/notification` to AWS Lambda. Triggered on push to `main` when `services/notification/**` changes. |

### Infrastructure

| File | Status | Purpose |
|------|--------|---------|
| `infra-plan.yml` | Planned | Run `terraform plan` on PRs that touch `infrastructure/**`. Posts the plan as a PR comment. |
| `infra-apply.yml` | Planned | Run `terraform apply` on merge to `main` for `infrastructure/**` changes. Requires explicit approval via GitHub Environment protection rule. |
| `infra-destroy.yml` | Planned | Manually triggered (`workflow_dispatch` only). Tears down the dev environment. Requires approval. |

### Future enhancements (not yet scoped)

| Idea | Status | Notes |
|---|---|---|
| Claude AI PR reviewer | Idea | GitHub Actions workflow that calls the Claude API on every PR open/update, posts inline review comments. Requires `ANTHROPIC_API_KEY` repo secret and a custom workflow. Would run automatically without needing to invoke `/review` manually. Evaluate when first feature PRs are opened in Stage 1. |

---

## Trigger strategy (summary)

- Per-service workflows trigger on path filters (`services/<name>/**`) to
  avoid unnecessary builds across the monorepo.
- Infrastructure workflows trigger on `infrastructure/**` path filters.
- `infra-apply.yml` and `infra-destroy.yml` are gated by a GitHub Environment
  (`dev`) with a required reviewer — no unattended applies.
- All workflows assume an OIDC-federated IAM role; the role ARN will be stored
  as a repository secret (`AWS_ROLE_ARN`), not a key/secret pair.
