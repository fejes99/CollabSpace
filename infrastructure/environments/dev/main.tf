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

# ── Service sets ────────────────────────────────────────────────────────────────
#
# ecs_services: the four long-running containers managed by ECS Fargate.
# all_services: adds the Lambda notification service, which needs a log group
#               but not an ECS task role.

locals {
  ecs_services = toset([
    "auth-workspace",
    "document-service",
    "realtime-service",
    "ai-assistant",
  ])

  all_services = toset(concat(tolist(local.ecs_services), ["notification"]))
}

# ── VPC ─────────────────────────────────────────────────────────────────────────
# ADR-009: ECS tasks run in public subnets (no NAT Gateway) for cost reasons.
# ADR-010: Two AZs in dev; the module accepts a list so a third can be added
#          by changing only the variable values below.

module "vpc" {
  source = "../../modules/vpc"

  project_name = var.project_name
  environment  = var.environment

  azs = ["eu-central-1a", "eu-central-1b"]

  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
}

# ── Security groups ──────────────────────────────────────────────────────────────

module "security_groups" {
  source = "../../modules/security-groups"

  project_name = var.project_name
  environment  = var.environment
  vpc_id       = module.vpc.vpc_id
}

# ── ECS IAM roles ────────────────────────────────────────────────────────────────

module "iam_ecs" {
  source = "../../modules/iam-ecs"

  project_name   = var.project_name
  environment    = var.environment
  services       = local.ecs_services
  aws_region     = var.aws_region
  aws_account_id = data.aws_caller_identity.current.account_id
}

# ── CloudWatch log groups ────────────────────────────────────────────────────────

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project_name       = var.project_name
  environment        = var.environment
  services           = local.all_services
  log_retention_days = var.log_retention_days
}
