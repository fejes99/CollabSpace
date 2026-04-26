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

# ── ECS cluster ──────────────────────────────────────────────────────────────────
# ADR-011: Container Insights is disabled in dev to stay within the $0-5/month
# budget. Enable in staging/prod where per-task metrics have operational value.

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  project_name              = var.project_name
  environment               = var.environment
  enable_container_insights = false
}

# ── Application Load Balancer ────────────────────────────────────────────────────
# The ALB is shared across all services. Each service attaches its own target
# group and listener rule via the ecs-service module. The ALB module itself has
# no knowledge of which services exist. See ADR-012.

module "alb" {
  source = "../../modules/alb"

  project_name      = var.project_name
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  alb_sg_id         = module.security_groups.alb_sg_id
}

# ── auth-workspace ECS service ───────────────────────────────────────────────────
# Walking skeleton: one task, minimum CPU/memory, catches all traffic (/*).
# The image tag :skeleton is a placeholder. The CI/CD pipeline will push the
# real image and register a new task definition revision on first deploy.
# ECR tags are immutable — :skeleton is used instead of :latest so that the
# pipeline can push a proper SHA-tagged image without tag collisions.

module "auth_workspace" {
  source = "../../modules/ecs-service"

  project_name = var.project_name
  environment  = var.environment
  service_name = "auth-workspace"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${data.terraform_remote_state.shared.outputs.ecr_repository_urls["auth-workspace"]}:skeleton"

  container_port = 8080
  cpu            = 256
  memory         = 512
  desired_count  = 1

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["auth-workspace"]

  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.public_subnet_ids
  security_group_ids = [module.security_groups.ecs_tasks_sg_id]

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/*"]
  listener_rule_priority = 100

  health_check_path = "/actuator/health"
  log_group_name    = module.cloudwatch.log_group_names["auth-workspace"]
  aws_region        = var.aws_region

  environment_variables = {
    SPRING_PROFILES_ACTIVE = var.environment
  }
}
