output "log_group_names" {
  description = "Map of service name → CloudWatch log group name. Referenced in ECS task definitions (awslogs-group)."
  value       = { for name, lg in aws_cloudwatch_log_group.services : name => lg.name }
}

output "log_group_arns" {
  description = "Map of service name → CloudWatch log group ARN. Used when granting IAM write access to a specific log group."
  value       = { for name, lg in aws_cloudwatch_log_group.services : name => lg.arn }
}
