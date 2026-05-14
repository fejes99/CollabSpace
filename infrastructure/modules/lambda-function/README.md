# module: lambda-function

Creates a Lambda function wired to the shared ALB. Designed for event-driven services that don't need a persistent container — currently used by the notification service.

## What it creates

| Resource | Description |
|---|---|
| `aws_lambda_function` | The Lambda function with a bootstrap placeholder ZIP |
| `aws_iam_role` | Lambda execution role (assumes `lambda.amazonaws.com`) |
| `aws_iam_role_policy_attachment` | Attaches `AWSLambdaBasicExecutionRole` (CloudWatch Logs access) |
| `aws_lambda_permission` | Allows the ALB to invoke the function (scoped to the target group ARN) |
| `aws_lb_target_group` | ALB target group with `target_type = "lambda"` |
| `aws_lb_target_group_attachment` | Registers the Lambda as a target |
| `aws_lb_listener_rule` | Routes matching path patterns to the Lambda target group |

## Usage

```hcl
module "notification" {
  source = "../../modules/lambda-function"

  project_name = var.project_name
  environment  = var.environment
  service_name = "notification"

  listener_arn           = module.alb.listener_arn
  path_patterns          = ["/notifications", "/notifications/*"]
  listener_rule_priority = 20

  health_check_path = "/notifications/health"
  log_group_name    = module.cloudwatch.log_group_names["notification"]
}
```

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `project_name` | string | — | Resource name prefix |
| `environment` | string | — | Deployment environment |
| `service_name` | string | — | Service identifier |
| `runtime` | string | `nodejs22.x` | Lambda managed runtime (see note below) |
| `handler` | string | `handler.handler` | Entrypoint (`<file>.<export>`) |
| `timeout` | number | `30` | Max execution seconds |
| `memory_size` | number | `128` | Memory in MB |
| `listener_arn` | string | — | Shared ALB listener ARN |
| `path_patterns` | list(string) | — | ALB path patterns to match |
| `listener_rule_priority` | number | — | ALB rule priority (lower wins) |
| `health_check_path` | string | `/notifications/health` | ALB health check path |
| `log_group_name` | string | — | Pre-created CloudWatch log group name |

## Outputs

| Name | Description |
|---|---|
| `function_name` | Function name for CI/CD `update-function-code` calls |
| `function_arn` | Function ARN |
| `invoke_arn` | Invocation ARN for API Gateway integration (Stage 2+) |
| `target_group_arn` | Target group ARN for CloudWatch alarms |
| `execution_role_arn` | Execution role ARN — attach additional policies here in Stage 2+ |

## Why: bootstrap placeholder ZIP

Lambda requires code at creation time. The module generates a minimal inline ZIP via `archive_file` so Terraform can create the function without depending on a CI artifact. The CI/CD pipeline replaces this on first deploy with the real ZIP.

`lifecycle { ignore_changes = [filename, source_code_hash] }` prevents subsequent `terraform apply` runs from reverting CI-deployed code back to the placeholder. Same rationale as `ignore_changes = [task_definition]` in the ecs-service module (ADR-012).

## Why: Lambda permission before target group attachment

The `aws_lb_target_group_attachment` has `depends_on = [aws_lambda_permission.alb]`. The ALB sends a health check invocation immediately after the attachment is created. Without the permission in place first, that invocation is rejected with `AccessDenied` and the target is marked unhealthy before it ever had a chance to respond.

## Note: runtime default is nodejs22.x, not nodejs24.x

Lambda supports `nodejs24.x` (see [AWS announcement](https://aws.amazon.com/blogs/compute/node-js-24-runtime-now-available-in-aws-lambda/)), but the Terraform AWS provider v5.100.0 hardcodes its validation list and does not include `nodejs24.x` yet. Passing `nodejs24.x` fails at `terraform plan` with a validation error, not at apply time.

To upgrade once HashiCorp ships provider support:
1. Update the `default` in `variables.tf` to `"nodejs24.x"`
2. Update `--target=node22` to `--target=node24` in `services/notification/package.json`
3. Run `terraform init -upgrade && terraform plan` to confirm

## Why: pre-created log group

The `logging_config.log_group` is wired to the log group created by the `cloudwatch` module. If omitted, Lambda auto-creates a log group with no tags and infinite retention — inconsistent with the rest of the platform. Pointing Lambda at the pre-created group ensures retention (7 days in dev) and tags are applied consistently.
