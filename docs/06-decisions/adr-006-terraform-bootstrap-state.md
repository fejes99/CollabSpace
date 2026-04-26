# ADR-006: Terraform Bootstrap State Storage

**Status:** Accepted
**Date:** 2026-04-25

---

## Context

Terraform requires a backend to store state. Before any backend exists, something must create it — the classic bootstrap problem. This ADR records where the bootstrap module's own state lives, and where all subsequent Terraform configs store their state.

Three layers of Terraform exist in this repo:

- `infrastructure/bootstrap/` — creates the S3 bucket and DynamoDB table used as the shared backend
- `infrastructure/shared/` — creates ECR repositories and the GitHub Actions OIDC provider
- `infrastructure/environments/dev/` — creates all application infrastructure

The question is: where does each layer store its own Terraform state?

---

## Decision

**Bootstrap (`infrastructure/bootstrap/`):** Use local state. The state file (`terraform.tfstate`) is excluded via the root `.gitignore` — it lives only on the machine that ran bootstrap. The Terraform code itself (all `.tf` files and `terraform.tfvars`) is committed and public; only the state file is excluded. This is the correct split: the code demonstrates intent and skill; the state file is an operational artefact with no value in version control.

**All other layers (`shared/`, `environments/dev/`):** Use the S3 remote backend with DynamoDB locking created by bootstrap. State lives in S3, not on disk, so there is no local state file to gitignore. Every config in these layers declares:

```hcl
terraform {
  backend "s3" {
    bucket         = "<bootstrap output: state_bucket_name>"
    key            = "<layer-specific path>"
    region         = "<bootstrap output: aws_region>"
    dynamodb_table = "<bootstrap output: lock_table_name>"
    encrypt        = true
  }
}
```

**The bootstrap runs against real AWS, not LocalStack.** The S3 bucket and DynamoDB table are permanent meta-infrastructure — they are never torn down unlike application resources. Cost is effectively zero: DynamoDB is always-free, and S3 state files are kilobytes.

---

## Alternatives Considered

### LocalStack backend for bootstrap

**Rejected.** LocalStack state cannot be used when applying against real AWS. This would require bootstrapping twice — once locally and once against real AWS — defeating the purpose.

### Terraform Cloud free tier as the bootstrap backend

**Rejected.** Introduces a third-party dependency and account registration for a one-time, single-developer operation. Adds no value over local state at this scale.

### Commit bootstrap state to git

**Rejected.** For the bootstrap specifically, the state contains only an S3 bucket and a DynamoDB table — neither produces secrets or credentials. The immediate risk is genuinely low. However, committing state is excluded as a convention: it conflates operational artefacts with source code, and the habit of never committing state is worth building early. The downside of the local-only approach (loss risk) is documented under Consequences.

### S3 backend for bootstrap via state migration

Bootstrap creates the S3 bucket with a local backend, then migrates its own state into S3 using `terraform init -migrate-state`. This is a legitimate pattern and eliminates the risk of losing local state entirely. The tradeoff is a two-phase first run (apply → migrate → re-init) which adds friction for a one-time, single-developer operation.

**Rejected for now.** The single-developer constraint makes local state an acceptable tradeoff. Revisit if a second developer needs to run bootstrap, or if the bucket/region needs to change and manual `terraform import` is undesirable.

---

## Consequences

**Positive:**

- No circular dependency: bootstrap runs cleanly with no pre-existing backend.
- All Terraform code is public and version-controlled — the portfolio and learning value is fully visible.
- Zero cost: DynamoDB always-free tier covers the locking table indefinitely; S3 state storage is kilobytes.
- Real AWS from day one: the backend reflects the actual deployment target, not a simulation.
- State locking is in place for all application infrastructure from the first apply.

**Negative:**

- Bootstrap state lives only on the machine that ran it. If that machine is lost and the bucket/table still exist, state can be reconstructed with `terraform import` but this is manual work (two resources: the S3 bucket and the DynamoDB table).
- A second developer cannot run bootstrap independently without first coordinating to avoid creating duplicate resources.
- The `*.tfstate` and `*.tfstate.backup` gitignore rules must be present in the root `.gitignore` before anyone runs `terraform apply` in bootstrap; an absent rule risks accidentally committing state.

---

## Revisit When

- A second developer joins and needs to run or modify the bootstrap.
- The bootstrap state file is lost (document the `terraform import` commands at that point).
- Terraform Cloud or another managed backend becomes free and frictionless enough to replace this setup.
