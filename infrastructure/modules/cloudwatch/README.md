# Module: cloudwatch

Creates CloudWatch log groups for each service with a retention policy.

## What it creates

One `aws_cloudwatch_log_group` per service, named `/collabspace/{environment}/{service}`.

## Why pre-create log groups?

CloudWatch Logs creates a log group automatically the first time a service writes a log. The problem: automatically created log groups have **no retention policy** and accumulate logs forever.

CloudWatch charges $0.03/GB/month for stored logs. A service that crashes in a loop, or runs normally for months, accumulates far more log data than is ever useful to read. 7-day retention in dev keeps storage in the free tier and cleans up automatically.

Pre-creating the groups in Terraform ensures the retention policy exists from the first log write, not from whenever someone notices the cost.

## Log group naming

```
/collabspace/dev/auth-workspace
/collabspace/dev/document-service
/collabspace/dev/realtime-service
/collabspace/dev/ai-assistant
/collabspace/dev/notification
```

This structure allows Log Insights to query all services in one environment:

```
filter @logGroup like /collabspace\/dev\//
```

And isolate a single service:

```
fields @timestamp, @message
| filter @logGroup = "/collabspace/dev/auth-workspace"
| sort @timestamp desc
```

The ECS task definition references the group name via the `awslogs-group` driver option.

## Usage

```hcl
module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project_name       = "collabspace"
  environment        = "dev"
  services           = toset(["auth-workspace", "document-service", "realtime-service", "ai-assistant", "notification"])
  log_retention_days = 7
}
```

## Inputs

| Variable | Type | Default | Description |
|---|---|---|---|
| `project_name` | string | — | Prefix used in log group names |
| `environment` | string | — | Environment name |
| `services` | set(string) | — | One log group created per service name |
| `log_retention_days` | number | `7` | Days before CloudWatch expires log events. Must be a value from CloudWatch's allowed list (1, 3, 5, 7, 14, 30, …). Validated at plan time. |

## Outputs

| Output | Used by |
|---|---|
| `log_group_names` | ECS task definitions (`awslogs-group` driver option) |
| `log_group_arns` | IAM policies that grant write access to specific log groups |
