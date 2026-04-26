variable "project_name" {
  description = "Short project identifier, used as a prefix on all ALB resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID. Required by target groups created outside this module."
  type        = string
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs to attach to the ALB. Must span at least two AZs — AWS rejects an ALB with subnets in fewer than two AZs."
  type        = list(string)
}

variable "alb_sg_id" {
  description = "Security group ID to attach to the ALB. Should allow inbound 80/443 from 0.0.0.0/0."
  type        = string
}
