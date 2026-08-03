terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket         = "collabspace-terraform-state-440808375671"
    key            = "shared/terraform.tfstate"
    region         = "eu-central-1"
    dynamodb_table = "collabspace-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}

# ── Scheduled nightly teardown of environments/dev ───────────────────────────
# See docs/06-decisions/adr-039-scheduled-nightly-teardown.md
#
# Lives here, not in environments/dev, deliberately: this module's own
# CodeBuild project and EventBridge schedule must survive every nightly
# destroy of the dev environment. Putting it inside environments/dev's own
# state meant the first real test run destroyed the very automation that was
# supposed to run again the next night — discovered by testing before trusting
# the schedule, not in production.

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

  # destroy never uses these values (see the environment block's comment in
  # the module) — only that they're set, so terraform destroy can build a
  # plan at all against environments/dev's secrets.auto.tfvars, which
  # CodeBuild's clone won't have.
  extra_environment_variables = {
    TF_VAR_neon_host     = "unused-destroy-placeholder"
    TF_VAR_neon_username = "unused-destroy-placeholder"
    TF_VAR_neon_password = "unused-destroy-placeholder"
    TF_VAR_neon_dbname   = "unused-destroy-placeholder"
    TF_VAR_redis_url     = "unused-destroy-placeholder"
    TF_VAR_github_pat    = "unused-destroy-placeholder"
  }
}
