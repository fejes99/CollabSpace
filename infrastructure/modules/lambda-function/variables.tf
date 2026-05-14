variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)."
  type        = string
}

variable "service_name" {
  description = "Service identifier (e.g. notification). Used in resource names and the Lambda function name."
  type        = string
}

variable "runtime" {
  description = "Lambda managed runtime identifier. Must match the Node.js version used in the service's CI build."
  type        = string
  default     = "nodejs22.x" # nodejs24.x pending hashicorp/aws provider support; upgrade once available
}

variable "handler" {
  description = "Lambda handler entrypoint in the format '<filename-without-extension>.<export>'. For an ESM module at handler.mjs exporting `handler`, use 'handler.handler'."
  type        = string
  default     = "handler.handler"
}

variable "timeout" {
  description = "Maximum execution time in seconds before Lambda terminates the invocation. ALB has a 60-second idle timeout; this must be less than that."
  type        = number
  default     = 30
}

variable "memory_size" {
  description = "Memory allocated to the function in MB. Lambda billing is duration × memory, so this also affects CPU allocation and cost. 128 MB is the minimum and sufficient for a lightweight event handler."
  type        = number
  default     = 128
}

variable "listener_arn" {
  description = "ARN of the shared ALB HTTP listener. The Lambda listener rule attaches here."
  type        = string
}

variable "path_patterns" {
  description = "List of path patterns this Lambda handles (e.g. ['/notifications', '/notifications/*'])."
  type        = list(string)
}

variable "listener_rule_priority" {
  description = "Listener rule priority. Lower numbers take precedence. Leave gaps between services so more specific rules can be inserted."
  type        = number
}

variable "health_check_path" {
  description = "HTTP path the ALB uses for Lambda health checks. The Lambda must return 200 for this path."
  type        = string
  default     = "/notifications/health"
}

variable "log_group_name" {
  description = "CloudWatch log group name for this function (e.g. /collabspace/dev/notification). Created in the cloudwatch module; passed here so Lambda writes to the pre-tagged group rather than auto-creating an untagged one."
  type        = string
}
