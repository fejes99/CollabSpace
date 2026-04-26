variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, prod)."
  type        = string
}

variable "services" {
  description = "Set of service names that each get their own ECS task role and SSM path scope."
  type        = set(string)
}

variable "aws_region" {
  description = "AWS region. Used to construct ARNs in IAM policy documents."
  type        = string
}

variable "aws_account_id" {
  description = "AWS account ID. Used to construct ARNs in IAM policy documents."
  type        = string
}
