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
  description = "ECR repository URLs from shared state. Used in ECS task definitions in Stage 1."
  value       = data.terraform_remote_state.shared.outputs.ecr_repository_urls
}

output "github_actions_role_arn" {
  description = "IAM role ARN from shared state that GitHub Actions assumes via OIDC."
  value       = data.terraform_remote_state.shared.outputs.github_actions_role_arn
}

# ── VPC ───────────────────────────────────────────────────────────────────────

output "vpc_id" {
  description = "VPC ID. Referenced by ECS services and any future module that needs the VPC boundary."
  value       = module.vpc.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet IDs (one per AZ). Used by the ALB and ECS services."
  value       = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnet IDs (one per AZ). Used by RDS and ElastiCache."
  value       = module.vpc.private_subnet_ids
}

# ── Security groups ───────────────────────────────────────────────────────────

output "alb_sg_id" {
  description = "Security group ID for the ALB."
  value       = module.security_groups.alb_sg_id
}

output "ecs_tasks_sg_id" {
  description = "Security group ID applied to all ECS Fargate tasks."
  value       = module.security_groups.ecs_tasks_sg_id
}

# ── IAM ───────────────────────────────────────────────────────────────────────

output "task_execution_role_arn" {
  description = "Shared ECS task execution role ARN. Every ECS task definition references this."
  value       = module.iam_ecs.task_execution_role_arn
}

output "task_role_arns" {
  description = "Map of service name → ECS task role ARN."
  value       = module.iam_ecs.task_role_arns
}

# ── CloudWatch ────────────────────────────────────────────────────────────────

output "log_group_names" {
  description = "Map of service name → CloudWatch log group name. Referenced in ECS task definitions."
  value       = module.cloudwatch.log_group_names
}

# ── ECS cluster ───────────────────────────────────────────────────────────────

output "ecs_cluster_name" {
  description = "ECS cluster name. Used in CI/CD workflows (aws ecs update-service --cluster <name>)."
  value       = module.ecs_cluster.cluster_name
}

# ── ALB ───────────────────────────────────────────────────────────────────────

output "alb_dns_name" {
  description = "Public DNS name of the ALB. Use this URL to test the walking skeleton: http://<alb_dns_name>/actuator/health"
  value       = module.alb.alb_dns_name
}

output "alb_listener_arn" {
  description = "HTTP listener ARN. Referenced by additional services that add their own listener rules."
  value       = module.alb.listener_arn
}

# ── ECS services ──────────────────────────────────────────────────────────────

output "auth_workspace_service_name" {
  description = "ECS service name for auth-workspace. Used in CI/CD: aws ecs update-service --service <name>."
  value       = module.auth_workspace.service_name
}
