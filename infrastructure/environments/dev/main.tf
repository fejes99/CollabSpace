terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
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

# ── RDS PostgreSQL ───────────────────────────────────────────────────────────────
# Single db.t3.micro instance shared by auth-workspace (auth_db) and ai-assistant
# (vector_db). Co-location keeps us within the single 750-hour free-tier allocation.
# A second instance would cost ~$15/month. → docs/04-infrastructure/cost-strategy.md
#
# Private subnets only — no internet route to the database. The RDS security group
# (already defined in the security-groups module) restricts inbound 5432 to the
# ECS tasks security group. → ADR-009

resource "random_password" "db_master" {
  length  = 24
  special = false # JDBC connection strings can misparse special chars in passwords
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-${var.environment}"
  subnet_ids = module.vpc.private_subnet_ids

  tags = {
    Name = "${var.project_name}-${var.environment}-db-subnet-group"
  }
}

resource "aws_db_instance" "main" {
  identifier = "${var.project_name}-${var.environment}"

  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t3.micro"

  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true # always encrypt at rest, even in dev

  db_name  = "auth_db"
  username = "collabspace"
  password = random_password.db_master.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [module.security_groups.rds_sg_id]

  publicly_accessible = false
  multi_az            = false # out of scope for dev → roadmap.md

  backup_retention_period = 7   # AWS default; free within free tier storage
  skip_final_snapshot     = true # dev environment; no value in a final snapshot
  deletion_protection     = false

  # Paid features — disabled to stay within $0-5/month budget
  performance_insights_enabled = false
  monitoring_interval          = 0

  apply_immediately = true # dev: apply changes now, not at the next maintenance window

  tags = {
    Name = "${var.project_name}-${var.environment}-postgres"
  }
}

# ── SSM parameters for RDS credentials ───────────────────────────────────────────
# Stored under /collabspace/{env}/db/ — shared prefix because auth-workspace and
# ai-assistant both connect to this same instance with these same credentials.
# The ECS task execution role already has ssm:GetParameter on /collabspace/* so
# no IAM changes are needed. → modules/iam-ecs/main.tf

resource "aws_ssm_parameter" "db_host" {
  name  = "/collabspace/${var.environment}/db/host"
  type  = "String"
  value = aws_db_instance.main.address

  tags = {
    Name = "/collabspace/${var.environment}/db/host"
  }
}

resource "aws_ssm_parameter" "db_port" {
  name  = "/collabspace/${var.environment}/db/port"
  type  = "String"
  value = "5432"

  tags = {
    Name = "/collabspace/${var.environment}/db/port"
  }
}

resource "aws_ssm_parameter" "db_username" {
  name  = "/collabspace/${var.environment}/db/username"
  type  = "String"
  value = aws_db_instance.main.username

  tags = {
    Name = "/collabspace/${var.environment}/db/username"
  }
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/collabspace/${var.environment}/db/password"
  type  = "SecureString"
  value = random_password.db_master.result

  tags = {
    Name = "/collabspace/${var.environment}/db/password"
  }
}

resource "aws_ssm_parameter" "db_name" {
  name  = "/collabspace/${var.environment}/db/name"
  type  = "String"
  value = "auth_db"

  tags = {
    Name = "/collabspace/${var.environment}/db/name"
  }
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

# ── realtime-service ECS service ─────────────────────────────────────────────
# Walking skeleton: one task, minimum CPU/memory, reachable at /realtime/*.
# Priority 40 — more specific than document-service's /documents/* at 50.
# The image tag :skeleton is a one-time bootstrap placeholder. CI/CD manages
# image updates via service-realtime.yml after the first deploy.

module "realtime_service" {
  source = "../../modules/ecs-service"

  project_name = var.project_name
  environment  = var.environment
  service_name = "realtime-service"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${data.terraform_remote_state.shared.outputs.ecr_repository_urls["realtime-service"]}:skeleton"

  container_port = 3001
  cpu            = 256
  memory         = 512
  desired_count  = 1

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["realtime-service"]

  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.public_subnet_ids
  security_group_ids = [module.security_groups.ecs_tasks_sg_id]

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/realtime", "/realtime/*"]
  listener_rule_priority = 40

  health_check_path = "/health"
  log_group_name    = module.cloudwatch.log_group_names["realtime-service"]
  aws_region        = var.aws_region

  environment_variables = {
    NODE_ENV  = "production"
    LOG_LEVEL = "info"
  }
}

# ── ai-assistant ECS service ─────────────────────────────────────────────────
# Walking skeleton: one task, minimum CPU/memory, reachable at /assistant/*.
# Priority 30 — more specific than realtime-service's /realtime/* at 40.
# The image tag :skeleton is a one-time bootstrap placeholder. CI/CD manages
# image updates via service-ai.yml after the first deploy.

module "ai_assistant" {
  source = "../../modules/ecs-service"

  project_name = var.project_name
  environment  = var.environment
  service_name = "ai-assistant"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${data.terraform_remote_state.shared.outputs.ecr_repository_urls["ai-assistant"]}:skeleton"

  container_port = 8001
  cpu            = 256
  memory         = 512
  desired_count  = 1

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["ai-assistant"]

  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.public_subnet_ids
  security_group_ids = [module.security_groups.ecs_tasks_sg_id]

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/assistant", "/assistant/*"]
  listener_rule_priority = 30

  health_check_path = "/health"
  log_group_name    = module.cloudwatch.log_group_names["ai-assistant"]
  aws_region        = var.aws_region

  environment_variables = {
    ENVIRONMENT = "production"
    LOG_LEVEL   = "info"
  }
}

# ── notification Lambda ───────────────────────────────────────────────────────
# Walking skeleton: one Lambda function reachable at /notifications/health via
# ALB. Priority 20 — more specific than realtime-service's /realtime/* at 40.
# On first apply, Terraform creates the function with a bootstrap placeholder
# ZIP. The CI/CD pipeline (service-notification.yml) replaces it on first deploy.
# Subsequent Terraform applies will not revert CI-deployed code (ignore_changes
# on filename and source_code_hash — see lambda-function module README).

module "notification" {
  source = "../../modules/lambda-function"

  project_name = var.project_name
  environment  = var.environment
  service_name = "notification"

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/notifications", "/notifications/*"]
  listener_rule_priority = 20

  health_check_path = "/notifications/health"
  log_group_name    = module.cloudwatch.log_group_names["notification"]
}

# ── document-service ECS service ─────────────────────────────────────────────
# Walking skeleton: one task, minimum CPU/memory, reachable at /documents/*.
# Priority 50 — more specific than auth-workspace's /* catch-all at 100.
# The image tag :skeleton is a one-time bootstrap placeholder. CI/CD manages
# image updates via service-document.yml after the first deploy.

module "document_service" {
  source = "../../modules/ecs-service"

  project_name = var.project_name
  environment  = var.environment
  service_name = "document-service"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${data.terraform_remote_state.shared.outputs.ecr_repository_urls["document-service"]}:skeleton"

  container_port = 3000
  cpu            = 256
  memory         = 512
  desired_count  = 1

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["document-service"]

  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.public_subnet_ids
  security_group_ids = [module.security_groups.ecs_tasks_sg_id]

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/documents", "/documents/*"]
  listener_rule_priority = 50

  health_check_path = "/health"
  log_group_name    = module.cloudwatch.log_group_names["document-service"]
  aws_region        = var.aws_region

  environment_variables = {
    NODE_ENV  = "production"
    LOG_LEVEL = "info"
  }
}
