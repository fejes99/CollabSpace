variable "project_name" {
  description = "Short project identifier, used as a prefix on all log group names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, prod)."
  type        = string
}

variable "services" {
  description = "Set of service names. One log group is created per service."
  type        = set(string)
}

variable "log_retention_days" {
  description = "Number of days to retain log events before CloudWatch deletes them. Keep low in dev to avoid storage charges."
  type        = number
  default     = 7

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the values accepted by CloudWatch (1, 3, 5, 7, 14, 30, ...)."
  }
}
