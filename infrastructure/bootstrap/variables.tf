variable "aws_region" {
  description = "AWS region for infrastructure resources (S3 state bucket, DynamoDB lock table). Billing alarm is always created in us-east-1 regardless of this value — see provider alias in main.tf."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Project name used as a prefix for all resource names."
  type        = string
  default     = "collabspace"
}

variable "alert_email" {
  description = "Email address that receives billing alarm notifications. Confirm the SNS subscription after first apply."
  type        = string
  default     = "david.fejes@gmail.com"
}
