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

# auth-workspace has real CI/CD-built images (commit-SHA tags); no :skeleton
# image was ever pushed for it. This data source resolves the most recently
# pushed image so fresh dev-up always boots the current build. Every
# terraform plan queries ECR — if CI/CD pushed a new image since last apply,
# Terraform will show a task definition replacement on the next dev-up.
data "aws_ecr_image" "auth_workspace_latest" {
  repository_name = "collabspace-auth-workspace"
  most_recent     = true
}

# ── Service sets ─────────────────────────────────────────────────────────────

locals {
  ecs_services = toset([
    "auth-workspace",
    "document-service",
    "realtime-service",
    "ai-assistant",
  ])

  all_services = toset(concat(tolist(local.ecs_services), ["notification"]))

  # JWT audience — fixed per environment.
  # The issuer is the API Gateway endpoint (set inside the api-gateway module)
  # so it is not declared here. Tokens are invalidated on dev-down/dev-up
  # because the endpoint URL changes each cycle; acceptable in dev.
  jwt_audience = "collabspace-api"
}

# ── VPC ──────────────────────────────────────────────────────────────────────
# ADR-009: ECS tasks in public subnets (no NAT Gateway).
# ADR-010: Two AZs in dev.

module "vpc" {
  source = "../../modules/vpc"

  project_name = var.project_name
  environment  = var.environment

  azs = ["eu-central-1a", "eu-central-1b"]

  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
}

# ── Security groups ───────────────────────────────────────────────────────────

module "security_groups" {
  source = "../../modules/security-groups"

  project_name = var.project_name
  environment  = var.environment
  vpc_id       = module.vpc.vpc_id
}

# ── SSM parameters — Neon PostgreSQL credentials ──────────────────────────────
# Values sourced from secrets.auto.tfvars (gitignored). SSM paths are unchanged
# from the previous RDS setup so application config in PR 2 needs no adjustment.

resource "aws_ssm_parameter" "db_host" {
  name  = "/collabspace/${var.environment}/db/host"
  type  = "String"
  value = var.neon_host
  tags  = { Name = "/collabspace/${var.environment}/db/host" }
}

resource "aws_ssm_parameter" "db_port" {
  name  = "/collabspace/${var.environment}/db/port"
  type  = "String"
  value = "5432"
  tags  = { Name = "/collabspace/${var.environment}/db/port" }
}

resource "aws_ssm_parameter" "db_username" {
  name  = "/collabspace/${var.environment}/db/username"
  type  = "String"
  value = var.neon_username
  tags  = { Name = "/collabspace/${var.environment}/db/username" }
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/collabspace/${var.environment}/db/password"
  type  = "SecureString"
  value = var.neon_password
  tags  = { Name = "/collabspace/${var.environment}/db/password" }
}

resource "aws_ssm_parameter" "db_name" {
  name  = "/collabspace/${var.environment}/db/name"
  type  = "String"
  value = var.neon_dbname
  tags  = { Name = "/collabspace/${var.environment}/db/name" }
}

# ── SSM parameters — API Gateway auth ────────────────────────────────────────
#
# jwt_issuer: auth-workspace reads this at startup and sets it as the `iss`
# claim in every JWT it issues. The value matches the JWT Authorizer config on
# API Gateway so tokens round-trip correctly.
#
# internal_token: the shared secret API Gateway injects as X-Internal-Token on
# every forwarded request. Services read this at startup and validate the header
# on every request. random_password generates a 32-char alphanumeric value —
# no special characters so it is safe as an HTTP header value.

resource "random_password" "internal_token" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "jwt_issuer" {
  name      = "/collabspace/${var.environment}/jwt/issuer"
  type      = "String"
  value     = module.api_gateway.api_endpoint
  overwrite = true
  tags      = { Name = "/collabspace/${var.environment}/jwt/issuer" }
}

resource "aws_ssm_parameter" "jwt_audience" {
  name      = "/collabspace/${var.environment}/jwt/audience"
  type      = "String"
  value     = local.jwt_audience
  overwrite = true
  tags      = { Name = "/collabspace/${var.environment}/jwt/audience" }
}

resource "aws_ssm_parameter" "internal_token" {
  name  = "/collabspace/${var.environment}/api/internal-token"
  type  = "SecureString"
  value = random_password.internal_token.result
  tags  = { Name = "/collabspace/${var.environment}/api/internal-token" }
}

# ── SSM parameter — Upstash Redis ─────────────────────────────────────────────
# Value sourced from secrets.auto.tfvars (gitignored). See ADR-030: one shared
# Upstash instance for auth-workspace and (later) realtime-service, isolated by
# key-naming convention rather than ACL (ACL requires a paid Upstash plan).

resource "aws_ssm_parameter" "redis_url" {
  name  = "/collabspace/${var.environment}/redis/url"
  type  = "SecureString"
  value = var.redis_url
  tags  = { Name = "/collabspace/${var.environment}/redis/url" }
}

# ── ECS IAM roles ─────────────────────────────────────────────────────────────

module "iam_ecs" {
  source = "../../modules/iam-ecs"

  project_name   = var.project_name
  environment    = var.environment
  services       = local.ecs_services
  aws_region     = var.aws_region
  aws_account_id = data.aws_caller_identity.current.account_id
}

# ── CloudWatch log groups ──────────────────────────────────────────────────────

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project_name       = var.project_name
  environment        = var.environment
  services           = local.all_services
  log_retention_days = var.log_retention_days
}

# ── ECS cluster ───────────────────────────────────────────────────────────────
# ADR-011: Container Insights disabled in dev.

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  project_name              = var.project_name
  environment               = var.environment
  enable_container_insights = false
}

# ── Cloud Map namespace ───────────────────────────────────────────────────────
#
# A private DNS namespace scoped to this VPC. Each ECS service registers a
# Cloud Map service under this namespace. API Gateway resolves live task IPs
# via the VPC Link by querying these Cloud Map services.
#
# "collabspace.local" is a private DNS name — it does not resolve from the
# public internet and requires no Route 53 hosted zone purchase.

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "collabspace.local"
  vpc  = module.vpc.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-namespace"
  }
}

# ── API Gateway HTTP API ──────────────────────────────────────────────────────
# ADR-026: Replaces the walking-skeleton ALB as the REST entry point.
# The module creates the HTTP API, VPC Link, and default stage.
# The JWT Authorizer is defined separately below to avoid a cold-start
# bootstrapping problem: AWS validates the OIDC discovery endpoint at
# authorizer creation time, which requires auth-workspace to be running.
# Integrations and routes are defined per-service below.

module "api_gateway" {
  source = "../../modules/api-gateway"

  project_name       = var.project_name
  environment        = var.environment
  vpc_link_sg_id     = module.security_groups.vpc_link_sg_id
  subnet_ids         = module.vpc.public_subnet_ids
  internal_token     = random_password.internal_token.result
  log_retention_days = var.log_retention_days
}

# ── SSM parameter — JWKS URI ──────────────────────────────────────────────────
#
# auth-workspace serves GET /.well-known/jwks.json. The JWT Authorizer on API
# Gateway fetches signing keys from this URL. The URL is derived from the API
# Gateway endpoint — it changes on each dev-down/dev-up, but Terraform always
# writes the current value here after API Gateway is created.
#
# auth-workspace reads this parameter at startup so it can populate the `iss`
# field correctly in OpenAPI docs and any internal JWKS references.

resource "aws_ssm_parameter" "jwks_uri" {
  name  = "/collabspace/${var.environment}/jwt/jwks-uri"
  type  = "String"
  value = module.api_gateway.jwks_uri
  tags  = { Name = "/collabspace/${var.environment}/jwt/jwks-uri" }
}

# ── auth-workspace ────────────────────────────────────────────────────────────
#
# Task role SSM policy: auth-workspace calls SsmConfigLoader at startup to read
# JWT config (private key, issuer, audience, jwks-uri). These are application-
# level SSM calls that use the task role, not the execution role.

resource "aws_iam_role_policy" "auth_workspace_ssm" {
  name = "ssm-read"
  role = "${var.project_name}-${var.environment}-auth-workspace-task"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "SSMRead"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters"]
        Resource = ["arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/collabspace/*"]
      },
      {
        Sid      = "KMSDecrypt"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = ["arn:aws:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
      }
    ]
  })

  depends_on = [module.iam_ecs]
}

module "auth_workspace" {
  source = "../../modules/ecs-service"

  project_name = var.project_name
  environment  = var.environment
  service_name = "auth-workspace"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${data.terraform_remote_state.shared.outputs.ecr_repository_urls["auth-workspace"]}@${data.aws_ecr_image.auth_workspace_latest.image_digest}"

  container_port = 8080
  cpu            = 256
  memory         = 512
  desired_count  = 1

  # 100%: with a container health check now defined (ADR-031), the default
  # 0% would let ECS stop the old (healthy) task before the new one passes
  # its readiness check - a real zero-healthy-task gap, reproduced live.
  # 100% forces ECS to start the new task, wait for it to become HEALTHY,
  # and only then stop the old one. Requires deployment_maximum_percent to
  # allow >100% (default 200%) so both tasks can briefly coexist.
  deployment_minimum_healthy_percent = 100

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["auth-workspace"]

  subnet_ids             = module.vpc.public_subnet_ids
  security_group_ids     = [module.security_groups.ecs_tasks_sg_id]
  cloud_map_namespace_id = aws_service_discovery_private_dns_namespace.main.id

  log_group_name = module.cloudwatch.log_group_names["auth-workspace"]
  aws_region     = var.aws_region

  environment_variables = {
    SPRING_PROFILES_ACTIVE    = var.environment
    SPRING_DATASOURCE_URL     = "jdbc:postgresql://${var.neon_host}/${var.neon_dbname}?sslmode=require&channel_binding=require"
    SPRING_DATASOURCE_USERNAME = var.neon_username
    JWT_PRIVATE_KEY_SSM_PATH  = "/collabspace/${var.environment}/auth/jwt-private-key"
    JWT_ISSUER_SSM_PATH       = "/collabspace/${var.environment}/jwt/issuer"
    JWT_AUDIENCE_SSM_PATH     = "/collabspace/${var.environment}/jwt/audience"
    JWT_JWKS_URI_SSM_PATH     = "/collabspace/${var.environment}/jwt/jwks-uri"
  }

  secrets = {
    SPRING_DATASOURCE_PASSWORD = aws_ssm_parameter.db_password.arn
    SPRING_DATA_REDIS_URL      = aws_ssm_parameter.redis_url.arn
  }

  # Measured cold start (JPA + Flyway + JWT key load + Tomcat) took ~122s in
  # practice — start_period must clear that comfortably or ECS will kill a
  # task that's still legitimately starting. See ADR-031.
  #
  # interval/timeout/retries tightened below the module default (15s/5s/3)
  # to 5s/3s/2. Verified live this does NOT shrink the startup-transition
  # flicker (start_period suppresses UNHEALTHY the whole time a task is
  # legitimately starting, regardless of interval/retries) - it's kept
  # because it's the correct setting for detecting a task that goes
  # unhealthy after a successful deploy, where start_period no longer
  # applies. See ADR-031's Empirical results section.
  health_check_command      = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health/readiness || exit 1"]
  health_check_start_period = 150
  health_check_interval     = 5
  health_check_timeout      = 3
  health_check_retries      = 2
}

# auth-workspace API Gateway integration — public routes only (no JWT authorizer
# attached to any route using this integration). See auth_workspace_protected
# below for the JWT-authorized counterpart used by /v1/auth/{proxy+} and
# /v1/workspaces/{proxy+}.
#
# VPC_LINK + Cloud Map: API Gateway routes through the VPC Link to live task
# IPs registered in Cloud Map. integration_uri is the Cloud Map service ARN.
#
# request_parameters:
#   X-Internal-Token: injected from the stage variable so services can verify
#     the request arrived through API Gateway. See api-gateway-trust.md.
#   X-Correlation-ID: injected from $context.requestId — a unique ID API
#     Gateway assigns to every request. The CorrelationIdFilter in Spring picks
#     this up and adds it to MDC for structured log correlation.
#
# Deliberately does NOT map $context.authorizer.jwt.claims.* here: none of the
# routes below (register, login, jwks, oidc-discovery, health) have a JWT
# authorizer attached, so that context simply does not exist for them —
# referencing it here would fail requests to every route on this integration,
# not just skip the mapping. See auth_workspace_protected for where those
# claims are actually mapped.
#
# remove:header.* strips X-User-Id/X-User-Workspaces/X-JWT-Jti from whatever
# the client sent, rather than leaving them unmapped. Unmapped would just pass
# a client-supplied value straight through unmodified — harmless today since
# no public route trusts those headers, but a filter that reads them without
# knowing which integration a request came through has no other way to tell a
# gateway-verified value apart from a client-forged one. Stripping here means
# they are only ever present when this integration's authorized counterpart
# (auth_workspace_protected) actually set them.

resource "aws_apigatewayv2_integration" "auth_workspace" {
  api_id             = module.api_gateway.api_id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = module.auth_workspace.cloud_map_service_arn
  connection_type    = "VPC_LINK"
  connection_id      = module.api_gateway.vpc_link_id

  request_parameters = {
    "overwrite:header.x-internal-token" = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id" = "$context.requestId"
    "remove:header.x-user-id"           = "''"
    "remove:header.x-user-workspaces"   = "''"
    "remove:header.x-jwt-jti"           = "''"
  }
}

# Public routes — no JWT Authorizer. These must be reachable without a token:
#   /v1/auth/register, /v1/auth/login: the client does not have a JWT yet.
#   /.well-known/jwks.json: the JWT Authorizer itself fetches from this URL.
#   /actuator/health: ALB and monitoring probes; must not require auth.

resource "aws_apigatewayv2_route" "auth_register" {
  api_id    = module.api_gateway.api_id
  route_key = "POST /v1/auth/register"
  target    = "integrations/${aws_apigatewayv2_integration.auth_workspace.id}"
}

resource "aws_apigatewayv2_route" "auth_login" {
  api_id    = module.api_gateway.api_id
  route_key = "POST /v1/auth/login"
  target    = "integrations/${aws_apigatewayv2_integration.auth_workspace.id}"
}

resource "aws_apigatewayv2_route" "auth_jwks" {
  api_id    = module.api_gateway.api_id
  route_key = "GET /.well-known/jwks.json"
  target    = "integrations/${aws_apigatewayv2_integration.auth_workspace.id}"
}

resource "aws_apigatewayv2_route" "auth_oidc_discovery" {
  api_id    = module.api_gateway.api_id
  route_key = "GET /.well-known/openid-configuration"
  target    = "integrations/${aws_apigatewayv2_integration.auth_workspace.id}"
}

# ── JWT Authorizer ────────────────────────────────────────────────────────────
#
# Defined here rather than inside the api-gateway module to solve a cold-start
# bootstrapping problem: AWS validates the OIDC discovery endpoint
# ({api_endpoint}/.well-known/openid-configuration) at authorizer creation time.
# That route must exist AND auth-workspace must be serving it. By depending on
# the OIDC route and the auth-workspace module, and polling until auth-workspace
# responds, we guarantee AWS can reach the endpoint when the authorizer is made.
#
# issuer = api_endpoint: tokens are invalidated on dev-down/dev-up because the
# API Gateway endpoint (and therefore the issuer URL) changes each cycle. This
# is acceptable in dev. Staging/prod should use a stable custom domain.

resource "terraform_data" "wait_for_oidc" {
  depends_on = [
    aws_apigatewayv2_route.auth_oidc_discovery,
    module.auth_workspace,
  ]

  triggers_replace = [module.api_gateway.api_endpoint]

  provisioner "local-exec" {
    command = <<-EOT
      echo "==> Waiting for auth-workspace OIDC discovery endpoint..."
      ENDPOINT="${module.api_gateway.api_endpoint}/.well-known/openid-configuration"
      for i in $(seq 1 30); do
        if curl -sf "$ENDPOINT" > /dev/null 2>&1; then
          echo "==> OIDC endpoint ready after $i attempt(s)"
          exit 0
        fi
        echo "==> Attempt $i/30: not ready, retrying in 10s..."
        sleep 10
      done
      echo "==> Timeout: OIDC discovery endpoint did not become ready in 5 minutes"
      exit 1
    EOT
  }
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  depends_on = [terraform_data.wait_for_oidc]

  api_id           = module.api_gateway.api_id
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]
  name             = "${var.project_name}-${var.environment}-jwt"

  jwt_configuration {
    issuer   = module.api_gateway.api_endpoint
    audience = [local.jwt_audience]
  }
}

resource "aws_apigatewayv2_route" "auth_health" {
  api_id    = module.api_gateway.api_id
  route_key = "GET /actuator/health"
  target    = "integrations/${aws_apigatewayv2_integration.auth_workspace.id}"
}

# auth-workspace API Gateway integration — JWT-authorized routes only.
#
# Split from auth_workspace above because request_parameters is set per
# integration, not per route, and $context.authorizer.jwt.claims.* only
# resolves on routes where the JWT authorizer actually ran. Mixing this into
# the public integration would break register/login/health/jwks, since that
# context variable is unresolvable there.
#
#   X-User-Id: the userId claim — see authentication.md §Claims structure.
#   X-User-Workspaces: the memberships claim. JwtService serializes this claim
#     as a JSON *string*, not a nested array — API Gateway's claim-to-header
#     mapping cannot carry an array/object value, only string/number/boolean.
#   X-JWT-Jti: the jti claim, forwarded so each service can check it against
#     the Redis blocklist on logout. See docs/02-architecture/authentication.md
#     §Token revocation.

resource "aws_apigatewayv2_integration" "auth_workspace_protected" {
  api_id             = module.api_gateway.api_id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = module.auth_workspace.cloud_map_service_arn
  connection_type    = "VPC_LINK"
  connection_id      = module.api_gateway.vpc_link_id

  request_parameters = {
    "overwrite:header.x-internal-token"  = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id"  = "$context.requestId"
    "overwrite:header.x-user-id"         = "$context.authorizer.jwt.claims.userId"
    "overwrite:header.x-user-workspaces" = "$context.authorizer.jwt.claims.memberships"
    "overwrite:header.x-jwt-jti"         = "$context.authorizer.jwt.claims.jti"
  }
}

# Protected routes — JWT required. Any request without a valid token is
# rejected by the JWT Authorizer with 401 before reaching the service.

resource "aws_apigatewayv2_route" "auth_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/auth/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.auth_workspace_protected.id}"
}

resource "aws_apigatewayv2_route" "workspaces_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/workspaces/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.auth_workspace_protected.id}"
}

# ── realtime-service ──────────────────────────────────────────────────────────
# Walking skeleton on ECS Fargate. Will migrate to EC2 + WebSocket ALB when
# realtime-service development begins. See ADR-020 and ADR-026.

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

  subnet_ids             = module.vpc.public_subnet_ids
  security_group_ids     = [module.security_groups.ecs_tasks_sg_id]
  cloud_map_namespace_id = aws_service_discovery_private_dns_namespace.main.id

  log_group_name = module.cloudwatch.log_group_names["realtime-service"]
  aws_region     = var.aws_region

  environment_variables = {
    NODE_ENV  = "production"
    LOG_LEVEL = "info"
  }
}

resource "aws_apigatewayv2_integration" "realtime_service" {
  api_id             = module.api_gateway.api_id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = module.realtime_service.cloud_map_service_arn
  connection_type    = "VPC_LINK"
  connection_id      = module.api_gateway.vpc_link_id

  request_parameters = {
    "overwrite:header.x-internal-token" = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id" = "$context.requestId"
  }
}

resource "aws_apigatewayv2_route" "realtime_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/realtime/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.realtime_service.id}"
}

# ── ai-assistant ──────────────────────────────────────────────────────────────

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

  subnet_ids             = module.vpc.public_subnet_ids
  security_group_ids     = [module.security_groups.ecs_tasks_sg_id]
  cloud_map_namespace_id = aws_service_discovery_private_dns_namespace.main.id

  log_group_name = module.cloudwatch.log_group_names["ai-assistant"]
  aws_region     = var.aws_region

  environment_variables = {
    ENVIRONMENT = "production"
    LOG_LEVEL   = "info"
  }
}

resource "aws_apigatewayv2_integration" "ai_assistant" {
  api_id             = module.api_gateway.api_id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = module.ai_assistant.cloud_map_service_arn
  connection_type    = "VPC_LINK"
  connection_id      = module.api_gateway.vpc_link_id

  request_parameters = {
    "overwrite:header.x-internal-token" = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id" = "$context.requestId"
  }
}

resource "aws_apigatewayv2_route" "assistant_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/assistant/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.ai_assistant.id}"
}

# ── notification Lambda ────────────────────────────────────────────────────────
# Lambda functions are invoked directly by API Gateway — no VPC Link needed.
# integration_type = "AWS_PROXY" passes the full HTTP event to the function.
# payload_format_version = "2.0" is the modern format; it populates the Lambda
# event with headers, queryStringParameters, and body in a structured object.

module "notification" {
  source = "../../modules/lambda-function"

  project_name = var.project_name
  environment  = var.environment
  service_name = "notification"

  log_group_name = module.cloudwatch.log_group_names["notification"]
}

# API Gateway must be granted permission to invoke the Lambda. source_arn
# scopes the permission to this specific API — no other API Gateway in the
# account can invoke this function.

resource "aws_lambda_permission" "api_gateway_notification" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.notification.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${module.api_gateway.api_execution_arn}/*/*"
}

resource "aws_apigatewayv2_integration" "notification" {
  api_id                 = module.api_gateway.api_id
  integration_type       = "AWS_PROXY"
  integration_uri        = module.notification.invoke_arn
  integration_method     = "POST"
  payload_format_version = "2.0"

  request_parameters = {
    "overwrite:header.x-internal-token" = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id" = "$context.requestId"
  }
}

resource "aws_apigatewayv2_route" "notifications_health" {
  api_id    = module.api_gateway.api_id
  route_key = "GET /notifications/health"
  target    = "integrations/${aws_apigatewayv2_integration.notification.id}"
}

resource "aws_apigatewayv2_route" "notifications_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/notifications/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.notification.id}"
}

# ── document-service ──────────────────────────────────────────────────────────

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

  subnet_ids             = module.vpc.public_subnet_ids
  security_group_ids     = [module.security_groups.ecs_tasks_sg_id]
  cloud_map_namespace_id = aws_service_discovery_private_dns_namespace.main.id

  log_group_name = module.cloudwatch.log_group_names["document-service"]
  aws_region     = var.aws_region

  environment_variables = {
    NODE_ENV  = "production"
    LOG_LEVEL = "info"
  }
}

resource "aws_apigatewayv2_integration" "document_service" {
  api_id             = module.api_gateway.api_id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = module.document_service.cloud_map_service_arn
  connection_type    = "VPC_LINK"
  connection_id      = module.api_gateway.vpc_link_id

  request_parameters = {
    "overwrite:header.x-internal-token" = "$stageVariables.internalToken"
    "overwrite:header.x-correlation-id" = "$context.requestId"
  }
}

resource "aws_apigatewayv2_route" "documents_proxy" {
  api_id             = module.api_gateway.api_id
  route_key          = "ANY /v1/documents/{proxy+}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
  target             = "integrations/${aws_apigatewayv2_integration.document_service.id}"
}

# ── Remote state from shared ──────────────────────────────────────────────────
# See docs/06-decisions/adr-008-cross-root-module-state-sharing.md

data "terraform_remote_state" "shared" {
  backend = "s3"

  config = {
    bucket = "collabspace-terraform-state-440808375671"
    key    = "shared/terraform.tfstate"
    region = "eu-central-1"
  }
}
