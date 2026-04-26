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
    key            = "environments/dev/terraform.tfstate"
    region         = "eu-central-1"
    dynamodb_table = "collabspace-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

# Read outputs published by infrastructure/shared/.
# See docs/06-decisions/adr-008-cross-root-module-state-sharing.md
data "terraform_remote_state" "shared" {
  backend = "s3"

  config = {
    bucket = "collabspace-terraform-state-440808375671"
    key    = "shared/terraform.tfstate"
    region = "eu-central-1"
  }
}
