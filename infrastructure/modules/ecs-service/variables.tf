variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)."
  type        = string
}

variable "service_name" {
  description = "Service identifier (e.g. auth-workspace). Used in resource names, log stream prefixes, and the container name inside the task definition."
  type        = string
}

variable "cluster_id" {
  description = "ECS cluster ID to place this service in."
  type        = string
}

variable "image_url" {
  description = "Full container image URL including tag (e.g. 123456789.dkr.ecr.eu-central-1.amazonaws.com/collabspace-auth-workspace:abc1234). The CI/CD pipeline updates the task definition with a new tag on each deploy; Terraform only sets this for the initial creation."
  type        = string
}

variable "container_port" {
  description = "Port the container listens on. Must match the application's server.port (Spring Boot default: 8080)."
  type        = number
}

variable "cpu" {
  description = "CPU units for the Fargate task (256 = 0.25 vCPU). Valid Fargate combinations: 256/512-2048, 512/1024-4096, 1024/2048-8192."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Memory in MB for the Fargate task. Must be a valid Fargate combination for the chosen cpu value."
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Number of task instances to keep running. Use make dev-start/dev-stop to scale individual services without changing this value."
  type        = number
  default     = 1
}

variable "task_execution_role_arn" {
  description = "ARN of the ECS task execution role. Used by the ECS agent to pull images from ECR and write logs to CloudWatch."
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the ECS task role. Used by application code running inside the container."
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs where ECS tasks will run. Must be public subnets (ADR-009: no NAT Gateway)."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security group IDs applied to ECS tasks. Should allow inbound only from the VPC Link security group."
  type        = list(string)
}

variable "cloud_map_namespace_id" {
  description = "Cloud Map private DNS namespace ID. Each task registers its IP here at startup so API Gateway can route requests to it via the VPC Link."
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch log group name for this service (e.g. /collabspace/dev/auth-workspace). Created in the cloudwatch module."
  type        = string
}

variable "aws_region" {
  description = "AWS region. Passed to the awslogs log driver so it knows which CloudWatch endpoint to write to."
  type        = string
}

variable "environment_variables" {
  description = "Map of non-secret environment variables to inject into the container. Secrets must go through SSM Parameter Store, not here."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Map of environment variable name → SSM parameter ARN. ECS injects each value at task startup via the execution role. Use for passwords, keys, and other values that must not appear in plain text in the task definition."
  type        = map(string)
  default     = {}
}

variable "deployment_minimum_healthy_percent" {
  description = "Minimum percentage of tasks that must remain healthy during a deployment. 0 allows a new task to start before the old one stops on a single-task service."
  type        = number
  default     = 0
}

variable "deployment_maximum_percent" {
  description = "Maximum percentage of tasks allowed during a deployment relative to desired_count. 200 means ECS can temporarily run twice the desired count while rolling."
  type        = number
  default     = 200
}

variable "health_check_command" {
  description = "Container-level health check command (ECS CMD-SHELL array), e.g. [\"CMD-SHELL\", \"curl -f http://localhost:8080/actuator/health/readiness || exit 1\"]. null (default) disables it — ECS then only tracks process RUNNING state, not application readiness, and Cloud Map registers the task the instant the container starts rather than when it's actually able to serve traffic."
  type        = list(string)
  default     = null
}

variable "health_check_interval" {
  description = "Seconds between health check attempts, once startPeriod has elapsed."
  type        = number
  default     = 15
}

variable "health_check_timeout" {
  description = "Seconds to wait for the health check command to return before treating it as a failure."
  type        = number
  default     = 5
}

variable "health_check_retries" {
  description = "Consecutive failures required before ECS marks the container UNHEALTHY."
  type        = number
  default     = 3
}

variable "health_check_start_period" {
  description = "Grace period in seconds during which failing health checks don't count against retries. Must exceed the application's real cold-start time, or ECS will kill tasks that are still legitimately starting."
  type        = number
  default     = 150
}
