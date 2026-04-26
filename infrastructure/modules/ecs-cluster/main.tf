# ── ECS Cluster ─────────────────────────────────────────────────────────────────
#
# An ECS cluster is a logical grouping of services and tasks. With Fargate,
# the cluster is mostly a name — AWS manages the underlying compute. There are
# no EC2 instances to provision, patch, or scale.
#
# Container Insights adds per-task CPU, memory, network, and storage metrics
# to CloudWatch. It costs ~$0.30/month for one service in dev and is disabled
# by default. See ADR-011 for the full cost/observability trade-off.

resource "aws_ecs_cluster" "main" {
  name = "${var.project_name}-${var.environment}"

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}
