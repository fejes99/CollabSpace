# ADR-008: Cross-Root-Module State Sharing via terraform_remote_state

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

CollabSpace's Terraform configuration is split into three independent root modules applied in order: `bootstrap/`, `shared/`, and `environments/dev/`. Each module owns its own state file and is applied as an isolated unit.

The dev environment needs outputs that were produced by `shared/` — specifically ECR repository URLs (to build image references in ECS task definitions) and the GitHub Actions IAM role ARN (for CI/CD reference). These values are not known until `shared/` has been applied, and they must not be re-declared or re-created in `environments/dev/`.

The question is: **how does `environments/dev/` consume values that live in `shared/`'s state?**

---

## Decision

Use Terraform's built-in **`terraform_remote_state` data source** to read outputs directly from the `shared/` state file stored in S3.

```hcl
data "terraform_remote_state" "shared" {
  backend = "s3"
  config = {
    bucket = "collabspace-terraform-state-440808375671"
    key    = "shared/terraform.tfstate"
    region = "eu-central-1"
  }
}
```

Values are then accessed as `data.terraform_remote_state.shared.outputs.<name>`. The consuming module references only what `shared/` explicitly exposes via its `outputs.tf` — it cannot read internal state.

---

## Alternatives Considered

### Hardcoded values in terraform.tfvars

Copy the ECR URLs and role ARN from `terraform output` in `shared/` and paste them into `environments/dev/terraform.tfvars` as plain variables.

**Rejected** for three reasons:

1. **Manual sync burden.** Any time `shared/` is modified and an output changes, a human must remember to update `terraform.tfvars` in every environment. There is no validation step that catches a stale value.
2. **No contract enforcement.** A typo in a pasted ARN fails silently at plan time with a confusing error rather than a clear "output not found" message.
3. **Violates single-source-of-truth.** The S3 state file is already the authoritative record of what `shared/` produced. Duplicating values into `tfvars` creates a second source that can diverge.

### SSM Parameter Store

`shared/` writes output values to SSM parameters after apply. `environments/dev/` reads them via `aws_ssm_parameter` data sources at plan time.

**Rejected** at this scale for the following reasons:

1. **Operational overhead with no practical benefit.** With one developer and one account, loose coupling between Terraform layers adds a write step (`shared/` must publish to SSM) and an extra AWS API call (`dev/` reads SSM) while providing no real decoupling — both layers still depend on the same AWS account and the same bootstrap infrastructure.
2. **Cost.** SSM Standard parameters are free, but the pattern still adds resources to manage and potential IAM policy surface to expand.
3. **`terraform_remote_state` already provides the same read-only contract.** SSM would be the right choice if `shared/` and `environments/dev/` were managed by different teams or different Terraform backends entirely.

**Revisit if:** A second team takes ownership of `shared/` and needs to version its API independently of the consumers.

### Re-querying AWS APIs directly (data sources)

Instead of reading state, use data sources like `data "aws_ecr_repository"` or `data "aws_iam_role"` to look up the resources by name at plan time.

**Rejected** for two reasons:

1. **Name coupling instead of output coupling.** Both approaches require a shared convention, but data sources couple on resource names (strings that can silently diverge) rather than on typed Terraform outputs (which fail loudly if renamed or removed).
2. **Extra API calls.** Each data source issues an AWS API call during plan. With many resources this compounds; `terraform_remote_state` is a single S3 read.

The exception: for resources that are genuinely not managed by Terraform (e.g., an existing manually-created resource), a data source is correct. For resources that `shared/` owns, reading its state is preferred.

---

## Consequences

**Positive:**

- `environments/dev/` always reads the exact value that `shared/` applied, not a copy of it. No manual sync step exists that could be forgotten.
- The consuming layer is limited to what `shared/` explicitly exports via `outputs.tf`. Internal state is not accessible. This enforces a clean interface between layers.
- A single S3 read at plan time; no additional AWS API calls for each consumed value.
- `terraform plan` in `environments/dev/` will fail immediately and clearly if the referenced state key does not exist or does not contain the expected output — making misconfiguration obvious rather than subtle.

**Negative:**

- **S3 key coupling.** The bucket name and state key path are hardcoded in both the `backend` block of `environments/dev/main.tf` and in the `terraform_remote_state` config block. If the `shared/` state key is ever changed, `environments/dev/` must be updated atomically. Document both locations when either is changed.
- **Plaintext outputs in state.** The S3 state file stores all outputs as plaintext JSON. This is acceptable as long as `shared/outputs.tf` never exposes sensitive values (secrets, private keys). Sensitive outputs must be marked `sensitive = true` in Terraform and fetched from SSM or Secrets Manager at runtime by the application — not passed through state. This constraint applies to all future additions to `shared/outputs.tf`.
- **Shared state is an implicit apply-time dependency.** `environments/dev/` can plan against stale shared state between applies without error. If `shared/` is currently mid-apply when `environments/dev/` plans, it reads whatever state was last committed to S3. In practice this is not a problem with one developer, but is worth noting.

---

## Revisit When

- A second AWS environment (staging, production) is added and needs to consume `shared/` outputs — apply this same pattern consistently.
- A second team or sub-project is created that needs to consume outputs from `environments/dev/` — follow the same pattern downward: define outputs in `environments/dev/outputs.tf` and read them via `terraform_remote_state` in the consumer.
- `shared/outputs.tf` grows to include potentially sensitive values — at that point evaluate whether those specific outputs should be routed through SSM instead of state passthrough.
