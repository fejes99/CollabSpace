output "environment" {
  description = "The environment name this root module manages."
  value       = var.environment
}

output "aws_account_id" {
  description = "AWS account ID this environment is deployed into."
  value       = data.aws_caller_identity.current.account_id
}

# ── Passed through from shared state ─────────────────────────────────────────

output "ecr_repository_urls" {
  description = "ECR repository URLs from shared state. Used in ECS task definitions."
  value       = data.terraform_remote_state.shared.outputs.ecr_repository_urls
}

output "github_actions_role_arn" {
  description = "IAM role ARN from shared state that GitHub Actions assumes via OIDC."
  value       = data.terraform_remote_state.shared.outputs.github_actions_role_arn
}

# ── API Gateway ───────────────────────────────────────────────────────────────

output "api_gateway_endpoint" {
  description = "Public HTTPS endpoint for all REST services (e.g. https://{id}.execute-api.eu-central-1.amazonaws.com). Use this URL for curl smoke tests and as the base URL in the frontend."
  value       = module.api_gateway.api_endpoint
}

output "jwks_uri" {
  description = "JWKS URI served by auth-workspace via API Gateway. The JWT Authorizer fetches signing keys from this URL. Changes on each dev-down/dev-up."
  value       = module.api_gateway.jwks_uri
}

# ── VPC ───────────────────────────────────────────────────────────────────────

output "vpc_id" {
  description = "VPC ID."
  value       = module.vpc.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet IDs (one per AZ). Used by ECS services and the VPC Link."
  value       = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnet IDs (one per AZ)."
  value       = module.vpc.private_subnet_ids
}

# ── Security groups ───────────────────────────────────────────────────────────

output "alb_sg_id" {
  description = "ALB security group ID. Reserved for the realtime-service WebSocket ALB — not currently in use. See ADR-026."
  value       = module.security_groups.alb_sg_id
}

output "ecs_tasks_sg_id" {
  description = "Security group ID applied to all ECS Fargate tasks."
  value       = module.security_groups.ecs_tasks_sg_id
}

# ── IAM ───────────────────────────────────────────────────────────────────────

output "task_execution_role_arn" {
  description = "Shared ECS task execution role ARN."
  value       = module.iam_ecs.task_execution_role_arn
}

output "task_role_arns" {
  description = "Map of service name → ECS task role ARN."
  value       = module.iam_ecs.task_role_arns
}

# ── CloudWatch ────────────────────────────────────────────────────────────────

output "log_group_names" {
  description = "Map of service name → CloudWatch log group name."
  value       = module.cloudwatch.log_group_names
}

# ── ECS ───────────────────────────────────────────────────────────────────────

output "ecs_cluster_name" {
  description = "ECS cluster name. Used in CI/CD workflows and make dev-start/dev-stop/dev-status."
  value       = module.ecs_cluster.cluster_name
}

output "auth_workspace_service_name" {
  description = "ECS service name for auth-workspace. Used in CI/CD: aws ecs update-service --service <name>."
  value       = module.auth_workspace.service_name
}
