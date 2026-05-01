# Terraform Bootstrap

Creates the shared Terraform state backend used by all other infrastructure layers in this repo.

## What it creates

| Resource | Name | Region | Purpose |
|---|---|---|---|
| S3 bucket | `collabspace-terraform-state-{account_id}` | `eu-central-1` | Stores Terraform state for all layers |
| DynamoDB table | `collabspace-terraform-locks` | `eu-central-1` | Prevents concurrent state writes |
| SNS topic + subscription | `collabspace-billing-alarm` | `us-east-1` | Delivers billing alert emails |
| CloudWatch alarm | `collabspace-monthly-spend-5usd` | `us-east-1` | Triggers at $5 USD estimated monthly spend |

The SNS topic and CloudWatch alarm are intentionally in `us-east-1`. AWS only publishes the `EstimatedCharges` billing metric in `us-east-1`, and a CloudWatch alarm can only trigger an SNS topic in the same region. Moving either resource to `eu-central-1` would cause the alarm to fire silently with no email delivered.

## This runs once

Bootstrap is not part of the regular session teardown/bring-up cycle. The S3 bucket and DynamoDB table are permanent — do not run `terraform destroy` here. Application infrastructure in `environments/dev/` is destroyed and recreated each session; bootstrap is not.

## Prerequisites

- AWS CLI configured (`aws configure` or environment variables)
- Terraform >= 1.9 installed
- Billing alerts enabled in your AWS account: **AWS Console → Billing → Billing Preferences → Receive Billing Alerts** (required for the CloudWatch alarm to receive metric data)

## First-time setup

```bash
# 1. Edit terraform.tfvars — set alert_email to your real address

# 2. Initialise (downloads AWS provider)
terraform init

# 3. Review what will be created
terraform plan

# 4. Apply
terraform apply
```

After apply: **check your email and confirm the SNS subscription.** Without confirming, the billing alarm will fire but no email will be delivered.

## Outputs

After a successful apply, note these values — you will paste them into the `backend "s3"` block of every other Terraform config in this repo:

```bash
terraform output
```

| Output              | Used in                                             |
| ------------------- | --------------------------------------------------- |
| `state_bucket_name` | `bucket` field in all `backend "s3"` blocks         |
| `lock_table_name`   | `dynamodb_table` field in all `backend "s3"` blocks |
| `aws_region`        | `region` field in all `backend "s3"` blocks         |

## Destroying and re-creating bootstrap

Only do this if you need to change the region or start fresh. Destroying bootstrap while other Terraform configs are using the S3 backend will corrupt their state.

```bash
# 1. Force-remove the S3 bucket including all versions and delete markers.
#    Plain `aws s3 rm --recursive` only adds delete markers on versioned
#    buckets and does not fully empty them — --force handles this correctly.
aws s3 rb s3://$(terraform output -raw state_bucket_name) --force

# 2. Destroy remaining bootstrap resources (DynamoDB, SNS, CloudWatch alarm)
terraform destroy

# 3. Update terraform.tfvars if needed (e.g. changing aws_region)

# 4. Re-create
terraform apply
```

If you are changing the region, all future `backend "s3"` blocks in `shared/` and `environments/dev/` must be updated to match the new bucket name and region.

## State file

Bootstrap itself uses local state (`terraform.tfstate`). This file is gitignored and lives only on your machine. If it is lost, the two resources can be re-imported:

```bash
terraform import aws_s3_bucket.terraform_state collabspace-terraform-state-{account_id}
terraform import aws_dynamodb_table.terraform_locks collabspace-terraform-locks
```
