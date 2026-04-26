output "vpc_id" {
  description = "ID of the VPC. Passed to every other module that creates resources inside the VPC."
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "List of public subnet IDs (one per AZ). Used by the ALB and ECS services."
  value       = [for s in aws_subnet.public : s.id]
}

output "private_subnet_ids" {
  description = "List of private subnet IDs (one per AZ). Used by RDS and ElastiCache."
  value       = [for s in aws_subnet.private : s.id]
}

output "public_route_table_id" {
  description = "ID of the public route table. Needed to attach additional VPC gateway endpoints."
  value       = aws_route_table.public.id
}
