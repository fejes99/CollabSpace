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
  description = "Maximum execution time in seconds. API Gateway HTTP API has a 30-second integration timeout — this must be <= 30."
  type        = number
  default     = 30
}

variable "memory_size" {
  description = "Memory allocated to the function in MB. Lambda billing is duration × memory. 128 MB is the minimum and sufficient for a lightweight event handler."
  type        = number
  default     = 128
}

variable "log_group_name" {
  description = "CloudWatch log group name for this function (e.g. /collabspace/dev/notification). Created in the cloudwatch module; passed here so Lambda writes to the pre-tagged group rather than auto-creating an untagged one."
  type        = string
}
