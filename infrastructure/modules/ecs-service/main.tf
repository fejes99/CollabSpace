# ── Cloud Map service registration ───────────────────────────────────────────
#
# Cloud Map is ECS Service Discovery. When a task starts, ECS registers its
# private IP as an A record in this service. When the task stops, ECS
# deregisters it. API Gateway resolves live task IPs through the VPC Link
# by querying Cloud Map — no load balancer sits in between.
#
# routing_policy = "MULTIVALUE": Cloud Map returns all registered (healthy)
# IPs in a single DNS response. API Gateway round-robins across them. Use
# WEIGHTED if you need traffic splitting (e.g. canary deploys).
#
# dns_records ttl = 10: short TTL so API Gateway picks up newly started or
# stopped tasks quickly. A long TTL would cause 502s when a task is replaced.
#
# health_check_custom_config: ECS-managed health. Cloud Map marks a task
# healthy when ECS reports it as RUNNING and unhealthy when ECS marks it
# STOPPED or DRAINING. This avoids Route 53 health check costs ($0.75 per
# endpoint per month). Adding a container-level healthCheck block to the
# task definition (see ADR-026 consequences) improves signal quality here.

resource "aws_service_discovery_service" "this" {
  name = var.service_name

  dns_config {
    namespace_id = var.cloud_map_namespace_id

    # A record: registers the task IP for standard DNS resolution.
    # SRV record: registers IP + container port so API Gateway VPC Link
    # integration knows which port to connect to (defaults to 80 without it).
    dns_records {
      ttl  = 10
      type = "A"
    }

    dns_records {
      ttl  = 10
      type = "SRV"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {}

  # AWS does not return health_check_custom_config in the GET response after
  # creation, so the provider perpetually plans a replacement. Ignore it after
  # the initial create — the config is set once and ECS manages it from there.
  lifecycle {
    ignore_changes = [health_check_custom_config]
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}

# ── Task definition ───────────────────────────────────────────────────────────
#
# A task definition is the blueprint for a container. ECS creates a new
# revision every time this definition changes. Old revisions are retained.
#
# network_mode = "awsvpc": required by Fargate. Each task gets its own ENI
# with its own IP address — the IP that Cloud Map registers.
#
# cpu / memory: expressed as strings at the task level (AWS API quirk). Valid
# Fargate combinations: 256/512-2048, 512/1024-4096, 1024/2048-8192.
#
# The container definition uses jsonencode() to keep the structure inspectable
# by Terraform's type system rather than using a heredoc string.

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

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }

      environment = [for k, v in var.environment_variables : { name = k, value = v }]
      secrets     = [for k, v in var.secrets : { name = k, valueFrom = v }]
    }
  ])

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}

# ── ECS service ───────────────────────────────────────────────────────────────
#
# The ECS service keeps var.desired_count copies of the task running. It
# replaces tasks that stop unexpectedly and manages rolling deployments.
#
# service_registries: tells ECS to register each task's IP in Cloud Map when
# the task reaches RUNNING state and deregister it on STOPPING/STOPPED. API
# Gateway discovers task IPs through this registry via the VPC Link.
#
# assign_public_ip = true: required because tasks are in public subnets with
# no NAT Gateway (ADR-009). Without a public IP, the task cannot reach ECR,
# CloudWatch, or SSM. The public IP is not reachable from the internet —
# the ECS tasks security group allows inbound only from the VPC Link SG.
#
# No load_balancer block: ALB target groups are gone. Routing is entirely
# through Cloud Map + API Gateway VPC Link. See ADR-026.
#
# No health_check_grace_period_seconds: this field only applies when a
# load_balancer block is present. Without it, the field is ignored (and
# raises a provider warning if set).
#
# ignore_changes on task_definition: CI/CD pushes new image tags and registers
# new task definition revisions. Terraform should not reset those back to the
# Terraform-managed revision on subsequent applies. See ADR-012.

resource "aws_ecs_service" "service" {
  name            = "${var.project_name}-${var.environment}-${var.service_name}"
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.service.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = true
  }

  # container_name and container_port: required when using SRV records.
  # ECS uses these to register the task's IP:port in Cloud Map so the API
  # Gateway VPC Link integration can connect to the correct container port.
  service_registries {
    registry_arn   = aws_service_discovery_service.this.arn
    container_name = var.service_name
    container_port = var.container_port
  }

  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  deployment_circuit_breaker {
    enable   = true
    rollback = false
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}
