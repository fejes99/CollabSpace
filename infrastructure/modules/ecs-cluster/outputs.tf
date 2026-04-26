output "cluster_id" {
  description = "ECS cluster ID. Passed to aws_ecs_service.cluster."
  value       = aws_ecs_cluster.main.id
}

output "cluster_name" {
  description = "ECS cluster name. Used in CloudWatch metrics and the AWS console."
  value       = aws_ecs_cluster.main.name
}

output "cluster_arn" {
  description = "ECS cluster ARN. Used when granting IAM permissions scoped to this cluster."
  value       = aws_ecs_cluster.main.arn
}
