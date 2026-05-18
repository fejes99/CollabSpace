output "service_name" {
  description = "ECS service name. Used in CI/CD workflows (aws ecs update-service --service <name>) and in make dev-start/dev-stop."
  value       = aws_ecs_service.service.name
}

output "service_id" {
  description = "ECS service ID (ARN). Used when referencing this service in IAM policies or CloudWatch alarms."
  value       = aws_ecs_service.service.id
}

output "task_definition_arn" {
  description = "ARN of the initial task definition revision created by Terraform. CI/CD creates subsequent revisions; this reflects only the Terraform-created version."
  value       = aws_ecs_task_definition.service.arn
}

output "cloud_map_service_arn" {
  description = "Cloud Map service ARN. Used as integration_uri in aws_apigatewayv2_integration resources when connecting API Gateway to this service via VPC Link."
  value       = aws_service_discovery_service.this.arn
}
