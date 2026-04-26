# Module: iam-ecs

Creates the IAM roles required for ECS Fargate tasks. Separates the two distinct identity layers that every ECS task carries.

## The two role types

### Task execution role (one, shared)

Used by the **ECS agent** — the AWS-managed infrastructure that runs before your container starts. Needs permission to:
- Pull the container image from ECR
- Write log streams to CloudWatch Logs
- Read SSM parameters to inject as environment variables at task startup

Your application code never uses this role. It is assumed by `ecs-tasks.amazonaws.com` automatically when the task starts.

### Task role (one per service)

Used by **your application code** at runtime. This is the identity the AWS SDK inside the container uses when it calls any AWS service (SSM, S3, SES, etc.).

Each service gets its own task role from day one so permissions can diverge as services grow (e.g. `ai-assistant` may need S3 access; `auth-workspace` may need SES). Starting with a shared task role and splitting it later is a painful refactor.

At the walking-skeleton stage all task roles are empty — only the trust policy exists. Permissions are added when individual services are built.

## What it creates

| Resource | Purpose |
|---|---|
| `task_execution` IAM role | Shared role assumed by the ECS agent |
| `AmazonECSTaskExecutionRolePolicy` attachment | Grants ECR pull + CloudWatch Logs write |
| `ssm-read` IAM policy | Grants SSM GetParameter on `/collabspace/*` |
| `ssm-read` policy attachment | Attaches SSM read policy to the execution role |
| `{service}-task` IAM role × N | Per-service role assumed by application code |

## Usage

```hcl
module "iam_ecs" {
  source = "../../modules/iam-ecs"

  project_name   = "collabspace"
  environment    = "dev"
  services       = toset(["auth-workspace", "document-service", "realtime-service", "ai-assistant"])
  aws_region     = "eu-central-1"
  aws_account_id = data.aws_caller_identity.current.account_id
}
```

## Inputs

| Variable | Type | Description |
|---|---|---|
| `project_name` | string | Prefix for resource names |
| `environment` | string | Environment name (dev, prod) |
| `services` | set(string) | ECS service names — one task role per entry |
| `aws_region` | string | Region used to construct SSM parameter ARNs |
| `aws_account_id` | string | Account ID used to construct SSM parameter ARNs |

## Outputs

| Output | Used by |
|---|---|
| `task_execution_role_arn` | Every ECS task definition (`executionRoleArn`) |
| `task_role_arns` | Map of `service → ARN`. Each task definition references its own entry (`taskRoleArn`) |

## Adding permissions to a task role

When a service needs to call an AWS service, add an inline policy to its task role in the environment's Terraform (not in this module — keep the module generic):

```hcl
resource "aws_iam_role_policy" "auth_workspace_ses" {
  name   = "ses-send"
  role   = module.iam_ecs.task_role_arns["auth-workspace"]
  policy = data.aws_iam_policy_document.ses_send.json
}
```
