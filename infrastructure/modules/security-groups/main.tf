# ── ALB security group ──────────────────────────────────────────────────────────
#
# The ALB is the only component that accepts traffic from the public internet.
# It listens on 80 (redirect to HTTPS) and 443 (HTTPS). At the walking-skeleton
# stage we only open 80 because there is no TLS certificate yet; 443 is a stub
# for when ACM is wired up.

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-${var.environment}-alb"
  description = "Controls inbound traffic from the internet to the ALB."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from anywhere - redirected to HTTPS by the ALB listener rule."
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

# ALB must be able to send health-check and forwarded requests to ECS tasks.
resource "aws_vpc_security_group_egress_rule" "alb_to_ecs" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward traffic to ECS tasks."
  ip_protocol                  = "tcp"
  from_port                    = 0
  to_port                      = 65535
  referenced_security_group_id = aws_security_group.ecs_tasks.id
}

# ── ECS tasks security group ────────────────────────────────────────────────────
#
# All ECS tasks share one security group. The inbound rule references the ALB
# security group by ID rather than by CIDR — this is the key pattern. When the
# ALB scales out and gets a new ENI with a new IP, the rule still covers it
# automatically because the rule is "from this security group", not "from
# this IP range".

resource "aws_security_group" "ecs_tasks" {
  name        = "${var.project_name}-${var.environment}-ecs-tasks"
  description = "Applied to all ECS Fargate tasks. Inbound from ALB only."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-ecs-tasks"
  }
}

resource "aws_vpc_security_group_ingress_rule" "ecs_from_alb" {
  security_group_id            = aws_security_group.ecs_tasks.id
  description                  = "Traffic forwarded by the ALB to the service port."
  ip_protocol                  = "tcp"
  from_port                    = 0
  to_port                      = 65535
  referenced_security_group_id = aws_security_group.alb.id
}

# ECS tasks need outbound HTTPS to reach:
#   - ECR API (image manifest)
#   - S3 (image layers, routed via the VPC gateway endpoint)
#   - CloudWatch Logs (log publishing)
#   - SSM Parameter Store (config at startup)
resource "aws_vpc_security_group_egress_rule" "ecs_https_out" {
  security_group_id = aws_security_group.ecs_tasks.id
  description       = "HTTPS to AWS service endpoints (ECR, CloudWatch, SSM)."
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# Upstash Redis is an external SaaS (not a VPC-internal service like ElastiCache),
# so it has no security group. Outbound port 6379 to Upstash's endpoint is added
# when the first service that uses Redis is built, scoped to Upstash's IP range.

# Tasks also need to reach RDS within the VPC (no internet route needed —
# intra-VPC traffic is covered by the local route regardless of SG egress,
# but explicit egress rules make intent clear and survive future SG audits).
resource "aws_vpc_security_group_egress_rule" "ecs_to_postgres" {
  security_group_id            = aws_security_group.ecs_tasks.id
  description                  = "PostgreSQL access to RDS (when provisioned)."
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.rds.id
}

# ── RDS security group ──────────────────────────────────────────────────────────
#
# Accepts PostgreSQL connections only from ECS tasks. No inbound from the
# internet, no inbound from humans directly — database access goes through
# the application layer only.

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-${var.environment}-rds"
  description = "PostgreSQL access from ECS tasks only. No public inbound."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.project_name}-${var.environment}-sg-rds"
  }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_ecs" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL connections from ECS tasks."
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.ecs_tasks.id
}
