# ── Target group ─────────────────────────────────────────────────────────────────
#
# A target group is a pool of endpoints (in this case: ECS task IP addresses)
# that the ALB forwards requests to. The ALB performs health checks against each
# registered target and only sends traffic to healthy ones.
#
# target_type = "ip" is required for Fargate tasks. Fargate uses awsvpc network
# mode, which gives each task its own ENI and IP address. The ALB cannot address
# tasks by EC2 instance ID (which is the "instance" target type) — there are no
# EC2 instances in Fargate. ECS automatically registers and deregisters task IPs
# in this group as tasks start and stop.
#
# The health check determines whether a task is healthy enough to receive traffic.
# If the check fails var.health_check_unhealthy_threshold times consecutively,
# the ALB stops routing to that task. ECS watches the target group health and
# replaces unhealthy tasks.

resource "aws_lb_target_group" "service" {
  # Target group names are limited to 32 characters. The full
  # "${project_name}-${environment}-${service_name}" pattern exceeds this in
  # staging/prod. substr(x, 0, 32) truncates safely if needed, though the
  # values in use today (collabspace-dev-auth-workspace = 30 chars) are safe.
  name        = substr("${var.project_name}-${var.environment}-${var.service_name}", 0, 32)
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = var.health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  # deregistration_delay: how long the ALB waits after removing a target before
  # stopping traffic to it. 30 seconds is enough for Spring Boot to drain in-
  # flight requests during a rolling deploy. Production should be higher (60–120s).
  deregistration_delay = 30

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}-tg"
    Service = var.service_name
  }
}

# ── Listener rule ────────────────────────────────────────────────────────────────
#
# A listener rule attaches this service to the shared ALB listener. When an
# incoming request's path matches var.path_patterns, the ALB forwards it to
# this service's target group.
#
# Lower priority numbers win. Priority 100 leaves room for more specific rules
# from other services (e.g., /api/documents/* at priority 10) to take precedence.
# For the walking skeleton, auth-workspace uses /* and catches all traffic.

resource "aws_lb_listener_rule" "service" {
  listener_arn = var.listener_arn
  priority     = var.listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service.arn
  }

  condition {
    path_pattern {
      values = var.path_patterns
    }
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}-rule"
    Service = var.service_name
  }
}

# ── Task definition ───────────────────────────────────────────────────────────────
#
# A task definition is the blueprint for a container: which image to run, how
# much CPU/memory to allocate, which ports to expose, where to send logs, and
# what IAM roles to use. ECS creates a new revision every time this definition
# changes. Old revisions are retained and can be rolled back to.
#
# network_mode = "awsvpc": Fargate requires this. Each task gets its own ENI
# (Elastic Network Interface) with its own IP address. This is what enables
# target_type = "ip" in the target group above.
#
# requires_compatibilities = ["FARGATE"]: prevents accidentally scheduling this
# task on EC2 instances. Validates the CPU/memory combination against Fargate's
# allowed values at plan time.
#
# cpu / memory: expressed as strings at the task level (AWS API quirk). Valid
# Fargate combinations: 256/512, 256/1024, 256/2048, 512/1024–4096, 1024/2048–8192.
# 256 CPU units = 0.25 vCPU. 512 MB is the minimum memory for 256 CPU.
#
# The container definition uses jsonencode() to produce the JSON string that
# AWS expects. This avoids heredoc strings and keeps the structure inspectable
# by Terraform's type system.

resource "aws_ecs_task_definition" "service" {
  family                   = "${var.project_name}-${var.environment}-${var.service_name}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = var.task_execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = var.image_url
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # awslogs driver ships container stdout/stderr to CloudWatch Logs.
      # awslogs-stream-prefix groups log streams by task: ecs/{container}/{task-id}.
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }

      # Static environment variables injected at task start.
      # Secrets (passwords, tokens) must NOT appear here — use the secrets block
      # and SSM Parameter Store instead (see iam-ecs module for SSM read policy).
      environment = [for k, v in var.environment_variables : { name = k, value = v }]
    }
  ])

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}

# ── ECS service ───────────────────────────────────────────────────────────────────
#
# The ECS service ensures that var.desired_count copies of the task definition
# are always running. If a task stops unexpectedly, ECS starts a replacement.
# During a deployment, ECS starts new tasks before stopping old ones (controlled
# by deployment_minimum_healthy_percent and deployment_maximum_percent).
#
# assign_public_ip = true: required because tasks are in public subnets with no
# NAT Gateway. Without a public IP, the task cannot reach ECR (to pull images),
# CloudWatch (to write logs), or SSM (to read config). See ADR-009.
#
# health_check_grace_period_seconds: gives the container time to start before
# the ALB begins health checking. Without a grace period, the ALB would mark the
# task unhealthy (because Spring Boot hasn't started yet) and ECS would kill it
# before it ever becomes healthy — a restart loop. 60 seconds is conservative
# for a Spring Boot app; tune down once startup time is measured.
#
# ignore_changes on task_definition: Terraform creates the initial task definition
# and service. After that, the CI/CD pipeline registers new task definition
# revisions and updates the service via aws ecs update-service. If Terraform
# managed the task definition on every apply, it would reset the service to the
# Terraform-defined image, overwriting what CI/CD deployed. See ADR-012.

resource "aws_ecs_service" "service" {
  name            = "${var.project_name}-${var.environment}-${var.service_name}"
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.service.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = true # ADR-009: no NAT gateway; public IP is how tasks reach ECR/SSM
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.service.arn
    container_name   = var.service_name
    container_port   = var.container_port
  }

  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent
  health_check_grace_period_seconds  = var.health_check_grace_period_seconds

  deployment_circuit_breaker {
    enable   = true
    rollback = false # no previous version to roll back to in the walking skeleton
  }

  # The listener rule must exist before ECS can register targets in the target
  # group. Without this, the first deployment may fail because the ALB has not
  # yet accepted the target group as a valid forwarding destination.
  depends_on = [aws_lb_listener_rule.service]

  lifecycle {
    # CI/CD updates the task definition after initial creation. Terraform should
    # not reset it on subsequent applies. See ADR-012.
    ignore_changes = [task_definition]
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}
