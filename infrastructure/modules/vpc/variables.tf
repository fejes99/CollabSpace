variable "project_name" {
  description = "Short project identifier, used as a prefix on all resource names."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, prod). Used in resource names and tags."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC. /16 gives 65,536 addresses — enough headroom for all subnets."
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "List of Availability Zone names to deploy into. Two AZs in dev; add a third for prod."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "One CIDR per AZ for public subnets (ALB + ECS tasks). Must match length of azs."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "One CIDR per AZ for private subnets (RDS, Redis). Must match length of azs."
  type        = list(string)
}
