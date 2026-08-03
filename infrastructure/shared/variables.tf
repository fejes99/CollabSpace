variable "aws_region" {
  description = "AWS region for shared resources. Must match the region used in bootstrap."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Project name used as a prefix for all resource names."
  type        = string
  default     = "collabspace"
}

variable "github_org" {
  description = "GitHub organisation or username that owns the repository. Used in the OIDC trust policy condition."
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name without the org prefix. Used in the OIDC trust policy condition."
  type        = string
}

variable "github_pat" {
  description = "GitHub personal access token (fine-grained, Contents:Read only, scoped to this one repo). Used by CodeBuild to clone the source for the nightly environments/dev destroy job. Source from secrets.auto.tfvars (gitignored)."
  type        = string
  sensitive   = true
}

variable "ecr_max_image_count" {
  description = "Number of images to retain per ECR repository. Images beyond this count are expired by the lifecycle policy."
  type        = number
  default     = 10
}
