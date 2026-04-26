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
  description = "CPU units for the Fargate task (256 = 0.25 vCPU). Valid Fargate combinations: 256/512-2048, 512/1024-4096, 1024/2048-8192. Smallest valid option is 256 CPU / 512 MB."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Memory in MB for the Fargate task. Must be a valid Fargate combination for the chosen cpu value."
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Number of task instances to keep running. Set to 1 for the walking skeleton. Scale up when adding load balancing requirements."
  type        = number
  default     = 1
}

variable "task_execution_role_arn" {
  description = "ARN of the ECS task execution role. Used by the ECS agent to pull images from ECR and write logs to CloudWatch. Created in the iam-ecs module."
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the ECS task role. Used by application code running inside the container. Created per-service in the iam-ecs module."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID. Required by the target group."
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs where ECS tasks will run. Must be public subnets in the walking skeleton (ADR-009: no NAT Gateway)."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security group IDs applied to ECS tasks. The ECS tasks security group should allow inbound from the ALB security group only."
  type        = list(string)
}

variable "listener_arn" {
  description = "ARN of the ALB HTTP listener. The service attaches its listener rule here."
  type        = string
}

variable "path_patterns" {
  description = "List of path patterns this service handles (e.g. ['/api/auth/*']). During the walking skeleton phase, use ['/*'] to catch all traffic."
  type        = list(string)
  default     = ["/*"]
}

variable "listener_rule_priority" {
  description = "Listener rule priority. Lower numbers take precedence. Leave gaps between services (10, 20, 30) so more specific rules can be inserted. Use 100 as the default catch-all for the first service."
  type        = number
  default     = 100
}

variable "health_check_path" {
  description = "HTTP path the ALB uses for health checks. Must return 200 when the service is healthy. Spring Boot Actuator exposes this at /actuator/health."
  type        = string
  default     = "/actuator/health"
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

variable "deployment_minimum_healthy_percent" {
  description = "Minimum percentage of tasks that must remain healthy during a deployment. Set to 0 for a single-task service so a new task can start before the old one stops."
  type        = number
  default     = 0
}

variable "deployment_maximum_percent" {
  description = "Maximum percentage of tasks allowed during a deployment (relative to desired_count). 200 means ECS can temporarily run twice the desired count while rolling."
  type        = number
  default     = 200
}

variable "health_check_grace_period_seconds" {
  description = "Seconds ECS waits after a task starts before the ALB begins health checks. Prevents premature task replacement during slow application startup. 60s is conservative for Spring Boot."
  type        = number
  default     = 60
}
