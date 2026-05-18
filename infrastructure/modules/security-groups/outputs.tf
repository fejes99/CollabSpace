output "vpc_link_sg_id" {
  description = "Security group ID for the API Gateway VPC Link ENIs. Pass to the api-gateway module as vpc_link_sg_id."
  value       = aws_security_group.vpc_link.id
}

output "alb_sg_id" {
  description = "Security group ID for the ALB. Reserved for the realtime-service WebSocket ALB — not currently attached to any load balancer (see ADR-026)."
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
