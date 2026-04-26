output "environment" {
  description = "The environment name this root module manages."
  value       = var.environment
}

output "aws_account_id" {
  description = "AWS account ID this environment is deployed into."
  value       = data.aws_caller_identity.current.account_id
}

# ── Passed through from shared state ─────────────────────────────────────────
# These outputs prove that terraform_remote_state resolved correctly.
# Run `terraform output` after init to verify the backend round-trip.

output "ecr_repository_urls" {
  description = "ECR repository URLs from shared state. Used in ECS task definitions in Stage 1."
  value       = data.terraform_remote_state.shared.outputs.ecr_repository_urls
}

output "github_actions_role_arn" {
  description = "IAM role ARN from shared state that GitHub Actions assumes via OIDC."
  value       = data.terraform_remote_state.shared.outputs.github_actions_role_arn
}
