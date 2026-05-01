# Module: ecs-cluster

Creates an ECS cluster — the logical grouping for all ECS services and tasks in an environment.

## What it creates

| Resource | Purpose |
|---|---|
| `aws_ecs_cluster` | Named cluster scoped to one environment |

With Fargate, the cluster is mostly a name and a settings container. AWS manages all underlying compute — there are no EC2 instances to provision, patch, or scale here.

## Container Insights

Container Insights adds per-task CPU, memory, network, and storage metrics to CloudWatch. It is controlled by the `enable_container_insights` variable.

| Setting | Cost | When to use |
|---|---|---|
| `false` (default) | $0 | Dev — CloudWatch logs are sufficient for debugging |
| `true` | ~$0.30/month per service | Staging/prod — needed for capacity planning and anomaly detection |

Disabled in dev to stay within the $0–5/month budget. See [ADR-011](../../../docs/06-decisions/adr-011-container-insights-dev.md) for the full cost/observability trade-off.

## Usage

```hcl
module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  project_name              = "collabspace"
  environment               = "dev"
  enable_container_insights = false
}
```

## Inputs

| Variable | Type | Default | Description |
|---|---|---|---|
| `project_name` | string | — | Prefix for the cluster name |
| `environment` | string | — | Environment name (dev, staging, prod) |
| `enable_container_insights` | bool | `false` | Enable CloudWatch Container Insights metrics |

## Outputs

| Output | Used by |
|---|---|
| `cluster_id` | `ecs-service` module — `cluster_id` variable |
| `cluster_name` | CloudWatch metrics, AWS console, CI/CD workflows (`aws ecs update-service --cluster`) |
| `cluster_arn` | IAM policies scoped to this cluster |
