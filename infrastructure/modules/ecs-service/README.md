# Module: ecs-service

Generic module that wires one containerised service into the environment. Call it once per service. It creates the full stack needed to get a container running behind the ALB.

## What it creates

| Resource | Purpose |
|---|---|
| `aws_lb_target_group` | Pool of task IPs the ALB forwards requests to; performs health checks |
| `aws_lb_listener_rule` | Attaches this service to the shared ALB listener on a path pattern |
| `aws_ecs_task_definition` | Container blueprint: image, CPU/memory, ports, logging, IAM roles |
| `aws_ecs_service` | Keeps N copies of the task running; replaces unhealthy tasks automatically |

## How the four resources connect

```
Internet → ALB listener (port 80)
             └─ listener_rule (path match) → target_group → [task IPs]
                                                               ↑
                                               ecs_service manages these
                                               (starts/stops tasks to match desired_count)
```

The ECS service registers task IPs in the target group as tasks start. The ALB polls the health check endpoint and only routes to healthy targets.

## Terraform vs CI/CD ownership

Terraform creates the **initial** task definition revision and ECS service. After that, the CI/CD pipeline owns the task definition: it builds a new image, registers a new revision with `aws ecs register-task-definition`, and calls `aws ecs update-service` to roll it out.

`lifecycle { ignore_changes = [task_definition] }` prevents Terraform from resetting the service to the Terraform-defined image on subsequent applies. Without this, a `terraform apply` would overwrite whatever CI/CD last deployed. See [ADR-012](../../../docs/06-decisions/adr-012-terraform-cicd-task-definition-ownership.md).

## Health check and grace period

The ALB sends HTTP requests to `health_check_path` every 30 seconds. A task must pass 2 consecutive checks to be considered healthy; 3 consecutive failures mark it unhealthy and stop traffic to it.

`health_check_grace_period_seconds` delays the first health check after a task starts. Without a grace period, a slow-starting application (Spring Boot takes ~15–30s) would be marked unhealthy and killed before it becomes ready — a restart loop. The default is 60s; tune it down once actual startup time is measured.

## Deployment configuration

| Variable | Default | Effect |
|---|---|---|
| `deployment_minimum_healthy_percent` | `0` | For `desired_count = 1`: allows stopping the old task before starting the new one (avoids needing spare capacity) |
| `deployment_maximum_percent` | `200` | Allows temporarily running 2× desired count during a rolling deploy |

The deployment circuit breaker is enabled: if a deployment fails (new tasks never become healthy), ECS stops the rollout. Automatic rollback is disabled at the walking skeleton stage because there is no previous healthy version to roll back to.

## Usage

```hcl
module "auth_workspace" {
  source = "../../modules/ecs-service"

  project_name = "collabspace"
  environment  = "dev"
  service_name = "auth-workspace"

  cluster_id = module.ecs_cluster.cluster_id
  image_url  = "${local.ecr_url}/collabspace-auth-workspace:skeleton"

  container_port = 8080
  cpu            = 256
  memory         = 512

  task_execution_role_arn = module.iam_ecs.task_execution_role_arn
  task_role_arn           = module.iam_ecs.task_role_arns["auth-workspace"]

  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.public_subnet_ids
  security_group_ids = [module.security_groups.ecs_tasks_sg_id]

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/*"]
  listener_rule_priority = 100

  health_check_path = "/actuator/health"
  log_group_name    = module.cloudwatch.log_group_names["auth-workspace"]
  aws_region        = "eu-central-1"
}
```

## Inputs

| Variable | Type | Default | Description |
|---|---|---|---|
| `project_name` | string | — | Prefix for all resource names |
| `environment` | string | — | Environment name (dev, staging, prod) |
| `service_name` | string | — | Service identifier (e.g. `auth-workspace`). Used in names, log streams, and the container name. |
| `cluster_id` | string | — | ECS cluster to place this service in |
| `image_url` | string | — | Full image URL with tag. Terraform sets this on initial creation; CI/CD manages it after. |
| `container_port` | number | — | Port the container listens on |
| `cpu` | number | `256` | CPU units (256 = 0.25 vCPU). Valid Fargate pairs: 256/512–2048, 512/1024–4096, 1024/2048–8192. |
| `memory` | number | `512` | Memory in MB. Must be a valid Fargate pair for the chosen `cpu`. |
| `desired_count` | number | `1` | Number of running task instances |
| `task_execution_role_arn` | string | — | Role used by the ECS agent (ECR pull, CloudWatch write, SSM read) |
| `task_role_arn` | string | — | Role used by application code inside the container |
| `vpc_id` | string | — | VPC ID for the target group |
| `subnet_ids` | list(string) | — | Subnets where tasks run. Must be public in dev (ADR-009: no NAT Gateway). |
| `security_group_ids` | list(string) | — | Security groups applied to tasks. Should allow inbound from the ALB SG only. |
| `listener_arn` | string | — | ALB listener ARN to attach the listener rule to |
| `path_patterns` | list(string) | `["/*"]` | Path patterns this service handles. Use specific prefixes (e.g. `["/api/auth/*"]`) when multiple services share the ALB. |
| `listener_rule_priority` | number | `100` | Rule priority. Lower wins. Leave gaps (10, 20, 30) for future insertions. |
| `health_check_path` | string | `/actuator/health` | Path the ALB polls for health. Must return 200 when healthy. |
| `log_group_name` | string | — | CloudWatch log group name. Created by the `cloudwatch` module. |
| `aws_region` | string | — | Region for the `awslogs` log driver |
| `environment_variables` | map(string) | `{}` | Non-secret env vars injected at task start. Secrets must go through SSM, not here. |
| `deployment_minimum_healthy_percent` | number | `0` | Minimum healthy task percentage during deployment |
| `deployment_maximum_percent` | number | `200` | Maximum task percentage during deployment |
| `health_check_grace_period_seconds` | number | `60` | Seconds before ALB starts health checking a new task |

## Outputs

| Output | Used by |
|---|---|
| `service_name` | CI/CD workflows — `aws ecs update-service --service <name>` |
| `service_id` | IAM policies scoped to this service; CloudWatch alarms |
| `task_definition_arn` | Reference only — reflects the Terraform-created revision, not what CI/CD last deployed |
| `target_group_arn` | CloudWatch alarms on unhealthy host count or target response time |
