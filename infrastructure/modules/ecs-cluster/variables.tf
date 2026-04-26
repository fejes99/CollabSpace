variable "project_name" {
  description = "Short project identifier, used as a prefix on the cluster name."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)."
  type        = string
}

variable "enable_container_insights" {
  description = "Enable CloudWatch Container Insights for per-task CPU/memory/network metrics. Costs ~$0.30/month per service in dev. Disable in dev to stay within the $0-5/month budget. See ADR-011."
  type        = bool
  default     = false
}
