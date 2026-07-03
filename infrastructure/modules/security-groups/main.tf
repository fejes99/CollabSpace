# ── VPC Link security group ───────────────────────────────────────────────────
#
# Attached to the VPC Link ENIs created by aws_apigatewayv2_vpc_link. Controls
# what traffic the VPC Link can send into the VPC.
#
# No inbound rules: traffic originating from API Gateway arrives through AWS's
# internal network to the ENI — it is not governed by the SG's inbound rules.
# The outbound rule is what matters: it allows the VPC Link to forward requests
# to ECS tasks on any TCP port (services expose different ports: 8080, 3000,
# 3001, 8001).
#
# The ECS tasks security group's inbound rule references this SG by ID, not by
# CIDR. This preserves the security group-to-security group pattern: if the VPC
# Link scales out and its ENIs get new IPs, the rule still applies automatically.

resource "aws_security_group" "vpc_link" {
  name        = "${var.project_name}-${var.environment}-vpc-link"
  description = "Attached to API Gateway VPC Link ENIs. Allows outbound to ECS tasks."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-vpc-link"
  }
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_ecs" {
  security_group_id            = aws_security_group.vpc_link.id
  description                  = "Forward API Gateway requests to ECS tasks."
  ip_protocol                  = "tcp"
  from_port                    = 0
  to_port                      = 65535
  referenced_security_group_id = aws_security_group.ecs_tasks.id
}

# ── ALB security group ────────────────────────────────────────────────────────
#
# Retained for the realtime-service WebSocket ALB (ADR-026, ADR-020). The ALB
# is not provisioned until realtime-service development begins. This SG is
# created now so it exists and can be referenced when that module is added.
#
# Port 80: HTTP. Port 443: HTTPS stub for when ACM is wired up.

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-${var.environment}-alb"
  description = "For the realtime-service WebSocket ALB (not yet provisioned - see ADR-026)."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from anywhere."
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from anywhere."
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# ── ECS tasks security group ─────────────────────────────────────────────────
#
# All ECS Fargate tasks share this security group. The inbound rule allows
# traffic only from the VPC Link security group — this is the only path into
# a task. API Gateway routes requests through the VPC Link ENIs, which are
# governed by vpc_link_sg. Any traffic that bypasses API Gateway (e.g., from
# another ECS task attempting a direct call) is blocked here.
#
# The X-Internal-Token header provides a secondary control for requests that
# do reach the service — see api-gateway-trust.md.

resource "aws_security_group" "ecs_tasks" {
  name        = "${var.project_name}-${var.environment}-ecs-tasks"
  description = "Applied to all ECS Fargate tasks. Inbound from VPC Link only."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-ecs-tasks"
  }
}

resource "aws_vpc_security_group_ingress_rule" "ecs_from_vpc_link" {
  security_group_id            = aws_security_group.ecs_tasks.id
  description                  = "Requests forwarded by the API Gateway VPC Link."
  ip_protocol                  = "tcp"
  from_port                    = 0
  to_port                      = 65535
  referenced_security_group_id = aws_security_group.vpc_link.id
}

# ECS tasks need outbound HTTPS to reach ECR, CloudWatch, and SSM.
resource "aws_vpc_security_group_egress_rule" "ecs_https_out" {
  security_group_id = aws_security_group.ecs_tasks.id
  description       = "HTTPS to AWS service endpoints (ECR, CloudWatch, SSM)."
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "ecs_to_postgres" {
  security_group_id = aws_security_group.ecs_tasks.id
  description       = "PostgreSQL to Neon (external managed service)."
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "ecs_to_redis" {
  security_group_id = aws_security_group.ecs_tasks.id
  description       = "Redis (TLS) to Upstash (external managed service). See ADR-030."
  ip_protocol       = "tcp"
  from_port         = 6379
  to_port           = 6379
  cidr_ipv4         = "0.0.0.0/0"
}
