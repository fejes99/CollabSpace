variable "aws_region" {
  description = "AWS region for dev environment resources. Must match the region used in bootstrap and shared."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Project name used as a prefix for all resource names."
  type        = string
  default     = "collabspace"
}

variable "environment" {
  description = "Environment name. Stamped into all resource tags and used in resource name prefixes."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}
