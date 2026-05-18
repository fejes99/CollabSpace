# ── HTTP API ──────────────────────────────────────────────────────────────────
#
# protocol_type = "HTTP" creates an API Gateway HTTP API (v2). This is the
# newer, cheaper API type. The alternative is REST API (v1), which has more
# features (request validation, usage plans, API keys) but costs more and is
# harder to configure. HTTP API is sufficient for all CollabSpace REST traffic.
#
# CORS: wildcard origins in dev. In staging/prod, restrict allow_origins to the
# frontend domain. allow_credentials must be false when allow_origins = ["*"].
#
# WebSocket traffic is NOT handled here. realtime-service uses a separate ALB
# per ADR-020 and ADR-026. Do not add protocol_type = "WEBSOCKET" to this API.

resource "aws_apigatewayv2_api" "main" {
  name          = "${var.project_name}-${var.environment}"
  protocol_type = "HTTP"
  description   = "REST entry point for all services. WebSocket (realtime-service) uses a separate ALB — see ADR-026."

  cors_configuration {
    allow_origins     = ["*"]
    allow_methods     = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
    allow_headers     = ["Authorization", "Content-Type", "X-Correlation-ID"]
    expose_headers    = ["X-Correlation-ID"]
    allow_credentials = false
    max_age           = 300
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-api"
  }
}

# ── VPC Link ──────────────────────────────────────────────────────────────────
#
# A VPC Link creates ENIs inside the VPC so API Gateway (which lives outside
# the VPC on AWS-managed infrastructure) can route requests to private resources
# without those requests traversing the public internet.
#
# HTTP API VPC Link (v2) has no hourly charge — only data transfer is billed.
# This is different from the REST API (v1) VPC Link which charges $0.01/hr.
#
# Creation takes 2–3 minutes. Terraform waits automatically. VPC Link is
# destroyed and recreated on each dev-down/dev-up; this is expected.
#
# subnet_ids: VPC Link ENIs are placed in these subnets. Since ECS tasks run
# in public subnets (ADR-009), the VPC Link is also placed there so the ENIs
# are in the same network tier and can route to task IPs.

resource "aws_apigatewayv2_vpc_link" "main" {
  name               = "${var.project_name}-${var.environment}"
  security_group_ids = [var.vpc_link_sg_id]
  subnet_ids         = var.subnet_ids

  tags = {
    Name = "${var.project_name}-${var.environment}-vpc-link"
  }
}

# ── JWT Authorizer ────────────────────────────────────────────────────────────
#
# Runs on every request that references this authorizer. The authorizer:
#   1. Reads Authorization: Bearer <token> from the request.
#   2. Fetches the JWKS from jwks_uri (cached; refreshes on cache miss or TTL).
#   3. Validates: signature (RS256), exp, iss, aud.
#   4. On success: extracts claims into $context.authorizer.claims.* and
#      forwards the request with X-User-Id and X-User-Workspaces headers.
#   5. On failure: returns 401 — request never reaches a downstream service.
#
# jwks_uri points to auth-workspace's JWKS endpoint via this same API Gateway.
# That route is configured WITHOUT this authorizer (public route) so the
# authorizer can fetch signing keys without a circular dependency.
#
# issuer: a fixed per-environment string. See ADR-026 for why this must not
# be the API Gateway URL itself (it changes on each dev-down/dev-up).

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.main.id
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]
  name             = "jwt"

  jwt_configuration {
    audience = [var.jwt_audience]
    issuer   = var.jwt_issuer
  }
}

# ── Default stage ─────────────────────────────────────────────────────────────
#
# name = "$default": a special stage name that means requests go directly to
# https://{api_id}.execute-api.{region}.amazonaws.com/{path} with no stage
# prefix in the URL. Without this, all paths would need a stage prefix
# (e.g. /dev/auth/login), which does not match the service route definitions.
#
# auto_deploy = true: any change to routes or integrations is deployed
# immediately without a manual deployment step. Correct for dev where fast
# iteration matters more than controlled rollouts.
#
# stage_variables.internalToken: the shared secret that each integration
# injects as X-Internal-Token via request_parameters. Downstream services
# validate this header to confirm the request arrived through API Gateway.
# The value is sensitive — Terraform marks it as such and it is sourced
# from a random_password resource, never hardcoded. See api-gateway-trust.md.

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.main.id
  name        = "$default"
  auto_deploy = true

  stage_variables = {
    internalToken = var.internal_token
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-stage-default"
  }
}
