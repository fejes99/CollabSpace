# ── Per-service log groups ──────────────────────────────────────────────────────
#
# Naming convention: /collabspace/{environment}/{service}
#
# This structure means:
#   - Log Insights can query all dev logs: filter by log group prefix /collabspace/dev/
#   - Each service is isolated: /collabspace/dev/auth-workspace has no overlap with
#     /collabspace/dev/document-service
#   - The ECS task definition's awslogs driver references this name directly.
#
# The retention policy is the only cost-control lever available for CloudWatch Logs.
# 7 days in dev means a task that crashes in a loop for a week still cleans up after
# itself. Prod should be 30-90 days depending on compliance requirements.

resource "aws_cloudwatch_log_group" "services" {
  for_each = var.services

  name              = "/collabspace/${var.environment}/${each.key}"
  retention_in_days = var.log_retention_days

  tags = {
    Name    = "/collabspace/${var.environment}/${each.key}"
    Service = each.key
  }
}
