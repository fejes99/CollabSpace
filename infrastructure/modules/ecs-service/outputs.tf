output "service_name" {
  description = "ECS service name. Used in CI/CD workflows (aws ecs update-service --service <name>)."
  value       = aws_ecs_service.service.name
}

output "service_id" {
  description = "ECS service ID (ARN). Used when referencing this service in IAM policies or CloudWatch alarms."
  value       = aws_ecs_service.service.id
}

output "task_definition_arn" {
  description = "ARN of the initial task definition revision created by Terraform. CI/CD creates subsequent revisions; this output reflects only the Terraform-created version."
  value       = aws_ecs_task_definition.service.arn
}

output "target_group_arn" {
  description = "Target group ARN. Referenced when creating CloudWatch alarms on target response times or unhealthy host counts."
  value       = aws_lb_target_group.service.arn
}
