# ── Application Load Balancer ────────────────────────────────────────────────────
#
# The ALB is the single public entry point for all HTTP traffic. It sits in
# public subnets, accepts traffic from the internet, and routes it to ECS tasks
# via target groups and listener rules.
#
# This module creates ONLY the ALB and its HTTP listener. Target groups and
# listener rules are created per-service in the ecs-service module. This keeps
# the ALB module stable — adding a new service does not require modifying it.
#
# drop_invalid_header_fields: rejects requests with malformed HTTP headers.
# This is a security best practice and costs nothing. AWS defaults it to false
# for backwards compatibility; enabling it here is a deliberate hardening choice.

resource "aws_lb" "main" {
  name               = "${var.project_name}-${var.environment}"
  load_balancer_type = "application"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [var.alb_sg_id]

  drop_invalid_header_fields = true

  # Deletion protection is off in dev so we can destroy the environment cleanly.
  # Enable this in prod before the first real deployment.
  enable_deletion_protection = false

  tags = {
    Name = "${var.project_name}-${var.environment}-alb"
  }
}

# ── HTTP Listener ────────────────────────────────────────────────────────────────
#
# The listener accepts inbound connections on port 80. Its default action is a
# 404 fixed response — not a forward to any target group. Each service adds its
# own listener rule (in the ecs-service module) with a path pattern condition.
# The rule with the lowest priority number wins when multiple rules match.
#
# Why 404 as the default and not 503?
# 503 (Service Unavailable) implies the server is overloaded or down.
# 404 (Not Found) is correct: if no rule matches the path, the resource simply
# doesn't exist at this ALB. It sets the right expectation.

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-listener-http"
  }
}
