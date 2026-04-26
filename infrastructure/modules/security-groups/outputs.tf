output "alb_sg_id" {
  description = "Security group ID for the Application Load Balancer."
  value       = aws_security_group.alb.id
}

output "ecs_tasks_sg_id" {
  description = "Security group ID applied to all ECS Fargate tasks."
  value       = aws_security_group.ecs_tasks.id
}

output "rds_sg_id" {
  description = "Security group ID for RDS PostgreSQL instances."
  value       = aws_security_group.rds.id
}
