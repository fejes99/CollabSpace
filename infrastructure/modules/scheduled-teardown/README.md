# Module: scheduled-teardown

Runs `terraform destroy` against a target environment on a nightly schedule, unconditionally. Backstop for the failure mode documented in [ADR-039](../../../docs/06-decisions/adr-039-scheduled-nightly-teardown.md): a dev-environment session left running because `make dev-down` wasn't run at session end. Does not replace that manual habit — it caps how long a forgotten session can run before it's automatically torn down.

**Instantiate this from a root module that is never itself destroyed by `terraform_working_directory`** — i.e. `infrastructure/shared`, not `infrastructure/environments/dev`. The first real test run put it in `environments/dev` and it destroyed itself on the very first execution, since its own CodeBuild project and schedule were part of the state being destroyed. See ADR-039's Implementation note.

## What it creates

| Resource | Purpose |
|---|---|
| `aws_codebuild_source_credential` | GitHub PAT registration so CodeBuild can clone the repo without a manual console authorization step |
| `codebuild_destroy` IAM role + policies | Runs the destroy: state backend access, per-service destroy permissions, its own build logs |
| `aws_codebuild_project.destroy` | Runs `terraform destroy` in `var.terraform_working_directory`. Buildspec is inline in this resource, not read from the repo — see ADR-039's rationale |
| `scheduler_invoke_codebuild` IAM role | Scoped to `codebuild:StartBuild` on exactly this one project — the scheduler itself never holds destroy permissions |
| `aws_scheduler_schedule.nightly_destroy` | EventBridge Scheduler cron trigger |

## Why CodeBuild, not Lambda or GitHub Actions

See ADR-039's Alternatives section. Short version: GitHub Actions `schedule:` cron would require widening the existing CI/deploy OIDC role to hold destroy permissions, and GitHub's scheduler is known to skip runs under load and disables itself after 60 days of repo inactivity. A Lambda would need a bundled Terraform binary and provider plugins. CodeBuild runs the official `hashicorp/terraform` image directly and keeps destroy permissions isolated from the deploy path entirely.

## Destroy permission scoping

Every mutating IAM statement in `destroy_permissions` is scoped to this project/environment by resource ARN name-prefix or, where AWS doesn't support that, by the `Environment` tag every resource carries via the environment root module's `default_tags`. A few read-only actions (`ec2:Describe*`, ECS `Describe*`/`List*`, `logs:DescribeLogGroups`) are necessarily `resources = ["*"]` because those AWS APIs don't support resource-level permissions at all — each is commented inline with why, matching the same documented pattern already used in `infrastructure/shared/oidc.tf`.

Validated against a real destroy run (2026-08-03) — including the account-scoped actions AWS doesn't support resource-level permissions for at all (`iam:GetPolicyVersion`, `ecr:DescribeImages`/`DescribeRepositories`), which only surfaced as `AccessDenied` once an actual destroy was attempted rather than just planned. `terraform plan` cannot catch missing IAM permissions — only a real run can. If a future statement proves too narrow, the failure mode is the same: `terraform destroy` fails loudly on that specific action, a safe failure (visible, leaves state intact) rather than a silent one.

## Usage

```hcl
# infrastructure/shared/main.tf — NOT infrastructure/environments/dev/main.tf.
# This module's own resources must outlive every destroy of the environment
# it targets.

module "scheduled_teardown" {
  source = "../modules/scheduled-teardown"

  project_name   = var.project_name
  environment    = "dev"
  aws_region     = var.aws_region
  aws_account_id = data.aws_caller_identity.current.account_id

  state_bucket                      = "collabspace-terraform-state-440808375671"
  state_key                         = "environments/dev/terraform.tfstate"
  state_lock_table                  = "collabspace-terraform-locks"
  additional_remote_state_read_keys = ["shared/terraform.tfstate"]

  terraform_working_directory = "infrastructure/environments/dev"
  github_repo_url             = "https://github.com/${var.github_org}/${var.github_repo}.git"
  github_pat                  = var.github_pat

  extra_environment_variables = {
    TF_VAR_neon_host     = "unused-destroy-placeholder"
    TF_VAR_neon_username = "unused-destroy-placeholder"
    TF_VAR_neon_password = "unused-destroy-placeholder"
    TF_VAR_neon_dbname   = "unused-destroy-placeholder"
    TF_VAR_redis_url     = "unused-destroy-placeholder"
    TF_VAR_github_pat    = "unused-destroy-placeholder"
  }
}
```

## Inputs

| Variable | Type | Description |
|---|---|---|
| `project_name`, `environment`, `aws_region`, `aws_account_id` | string | Standard naming/ARN inputs, same as every other module |
| `state_bucket`, `state_key`, `state_lock_table` | string | Must match the target environment's `backend "s3"` block exactly |
| `additional_remote_state_read_keys` | list(string) | S3 keys of other state files the target environment reads via `terraform_remote_state` (read-only access) — e.g. `shared/terraform.tfstate` |
| `terraform_working_directory` | string | Path within the repo to `cd` into before running destroy |
| `github_repo_url`, `github_source_branch` | string | Repo to clone and the branch to destroy from — always the branch you actually apply from |
| `github_pat` | string (sensitive) | Fine-grained PAT, `Contents: Read` only, scoped to this one repo. Source from `secrets.auto.tfvars` |
| `extra_environment_variables` | map(string) | `TF_VAR_*` placeholders for the target environment's required variables that are normally supplied by its own gitignored tfvars file — see the module's `environment` block comment for why placeholders are safe here |
| `schedule_expression`, `schedule_timezone` | string | Defaults to `cron(59 23 * * ? *)` / `Europe/Berlin` |

## Outputs

| Output | Used for |
|---|---|
| `codebuild_project_name` | Triggering a manual run: `aws codebuild start-build --project-name <output>` |
| `schedule_arn` | Pausing automation without destroying it: flip the schedule's `state` to `DISABLED` in the console, or add a `state` argument to the resource |

## Pausing without removing

To stop the nightly destroy temporarily (e.g. a multi-day working session) without tearing down this module's own resources, disable the schedule rather than running `terraform destroy` on this module:

```
aws scheduler update-schedule --name <schedule-name> --group-name default --state DISABLED ...
```
