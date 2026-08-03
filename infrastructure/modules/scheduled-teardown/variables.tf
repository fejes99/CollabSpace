variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment being torn down (dev, staging). Never point this at prod."
  type        = string
}

variable "aws_region" {
  description = "AWS region. Used to construct ARNs in IAM policy documents."
  type        = string
}

variable "aws_account_id" {
  description = "AWS account ID. Used to construct ARNs in IAM policy documents."
  type        = string
}

variable "state_bucket" {
  description = "S3 bucket holding the Terraform state for the environment being destroyed."
  type        = string
}

variable "state_key" {
  description = "S3 object key of the environment's Terraform state file, e.g. environments/dev/terraform.tfstate."
  type        = string
}

variable "state_lock_table" {
  description = "DynamoDB table used for Terraform state locking."
  type        = string
}

variable "terraform_working_directory" {
  description = "Path within the repository to run terraform destroy from, e.g. infrastructure/environments/dev."
  type        = string
}

variable "github_repo_url" {
  description = "HTTPS clone URL of the repository containing the Terraform configuration, e.g. https://github.com/org/repo.git."
  type        = string
}

variable "github_source_branch" {
  description = "Branch CodeBuild checks out before destroying. Should be the branch you actually apply from (main), never an arbitrary feature branch."
  type        = string
  default     = "main"
}

variable "github_pat" {
  description = "GitHub personal access token (fine-grained, Contents:Read only, scoped to this one repo) used by CodeBuild to clone the source. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

variable "schedule_expression" {
  description = "Cron expression for the nightly destroy, in EventBridge Scheduler's cron(...) syntax."
  type        = string
  default     = "cron(59 23 * * ? *)"
}

variable "schedule_timezone" {
  description = "IANA timezone the schedule_expression is evaluated in. EventBridge Scheduler handles DST transitions natively."
  type        = string
  default     = "Europe/Berlin"
}

variable "additional_remote_state_read_keys" {
  description = "S3 keys of other Terraform state files this environment reads via terraform_remote_state (read-only) — e.g. shared/terraform.tfstate, which environments/dev reads for ECR repo URLs and the GitHub Actions role ARN. Missed on the first implementation; destroy failed with S3 Forbidden until this was added."
  type        = list(string)
  default     = []
}

variable "extra_environment_variables" {
  description = "TF_VAR_* environment variables injected into the destroy job as PLAINTEXT, to satisfy required root-module variables that CodeBuild's git clone won't have (anything normally sourced from a gitignored tfvars file). Values should be harmless placeholders, never real secrets — destroy never reads them for anything meaningful."
  type        = map(string)
  default     = {}
}
