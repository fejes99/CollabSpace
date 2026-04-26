output "ecr_repository_urls" {
  description = "Map of service name to ECR repository URL. Reference in CI/CD workflows to tag and push images."
  value       = { for k, repo in aws_ecr_repository.services : k => repo.repository_url }
}

output "github_actions_role_arn" {
  description = "ARN of the IAM role assumed by GitHub Actions via OIDC. Set as role-to-assume in workflow files."
  value       = aws_iam_role.github_actions_ci.arn
}

output "oidc_provider_arn" {
  description = "ARN of the GitHub Actions OIDC identity provider registered in this AWS account."
  value       = aws_iam_openid_connect_provider.github_actions.arn
}
