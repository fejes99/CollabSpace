locals {
  services = toset([
    "auth-workspace",
    "document-service",
    "realtime-service",
    "ai-assistant",
  ])
}

resource "aws_ecr_repository" "services" {
  for_each = local.services

  name                 = "${var.project_name}-${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name        = "${var.project_name}-${each.key}"
    Environment = "global"
    Service     = each.key
    ManagedBy   = "terraform"
  }
}

resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Retain the last ${var.ecr_max_image_count} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_max_image_count
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
