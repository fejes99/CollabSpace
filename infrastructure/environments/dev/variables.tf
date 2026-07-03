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

variable "log_retention_days" {
  description = "CloudWatch log retention in days. 7 is sufficient for dev; increase for prod."
  type        = number
  default     = 7
}

variable "neon_host" {
  description = "Neon PostgreSQL host. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

variable "neon_username" {
  description = "Neon PostgreSQL username. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

variable "neon_password" {
  description = "Neon PostgreSQL password. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

variable "neon_dbname" {
  description = "Neon PostgreSQL database name. Source from secrets.auto.tfvars (gitignored)."
  type        = string
}

variable "redis_url" {
  description = "Upstash Redis connection URL. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

