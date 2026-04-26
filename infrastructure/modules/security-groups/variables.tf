variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, prod)."
  type        = string
}

variable "vpc_id" {
  description = "ID of the VPC in which to create security groups."
  type        = string
}
