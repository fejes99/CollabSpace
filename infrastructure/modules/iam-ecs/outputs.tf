output "task_execution_role_arn" {
  description = "ARN of the shared ECS task execution role. Referenced in every ECS task definition."
  value       = aws_iam_role.task_execution.arn
}

output "task_role_arns" {
  description = "Map of service name → task role ARN. Each ECS task definition references its own entry."
  value       = { for name, role in aws_iam_role.task : name => role.arn }
}
