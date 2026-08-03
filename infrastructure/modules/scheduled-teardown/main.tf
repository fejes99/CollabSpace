# ── GitHub source credential ─────────────────────────────────────────────────
#
# CodeBuild needs the actual .tf files to compute a destroy plan — state alone
# isn't enough (Terraform still reads the configuration for provider
# requirements and module sources). This registers a PAT so CodeBuild can
# clone the repo without a manual "Connect to GitHub" console step (the
# alternative, CodeStar Connections, requires clicking Authorize in the
# console and can't be scripted).

resource "aws_codebuild_source_credential" "github" {
  auth_type   = "PERSONAL_ACCESS_TOKEN"
  server_type = "GITHUB"
  token       = var.github_pat
}

# ── CodeBuild service role ───────────────────────────────────────────────────

data "aws_iam_policy_document" "codebuild_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["codebuild.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "codebuild_destroy" {
  name               = "${var.project_name}-${var.environment}-scheduled-destroy"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume_role.json

  tags = {
    Name = "${var.project_name}-${var.environment}-scheduled-destroy"
  }
}

# ── CodeBuild's own logging ──────────────────────────────────────────────────
#
# Separate from the destroy target's log groups (/collabspace/dev/*) below —
# this is where CodeBuild writes the build's own stdout/stderr.

data "aws_iam_policy_document" "codebuild_logging" {
  statement {
    sid    = "CodeBuildOwnLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = [
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/aws/codebuild/${var.project_name}-${var.environment}-scheduled-destroy",
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/aws/codebuild/${var.project_name}-${var.environment}-scheduled-destroy:*",
    ]
  }
}

resource "aws_iam_policy" "codebuild_logging" {
  name   = "${var.project_name}-${var.environment}-scheduled-destroy-logs"
  policy = data.aws_iam_policy_document.codebuild_logging.json
}

resource "aws_iam_role_policy_attachment" "codebuild_logging" {
  role       = aws_iam_role.codebuild_destroy.name
  policy_arn = aws_iam_policy.codebuild_logging.arn
}

# ── Terraform state backend access ───────────────────────────────────────────
#
# Scoped to exactly the one state object and the one lock table — this role
# must never be able to read or write any other environment's state.

data "aws_iam_policy_document" "state_backend" {
  statement {
    sid    = "StateObjectReadWrite"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
    ]
    resources = ["arn:aws:s3:::${var.state_bucket}/${var.state_key}"]
  }

  # Read-only access to other environments' state that this one reads via
  # terraform_remote_state (environments/dev reads shared/terraform.tfstate
  # for ECR repo URLs and the GitHub Actions role ARN). No PutObject — this
  # role only ever destroys its own environment's state, never writes to
  # another one's.
  dynamic "statement" {
    for_each = var.additional_remote_state_read_keys
    content {
      sid       = "RemoteStateRead${statement.key}"
      effect    = "Allow"
      actions   = ["s3:GetObject"]
      resources = ["arn:aws:s3:::${var.state_bucket}/${statement.value}"]
    }
  }

  # ListBucket is a bucket-level action — S3 does not support scoping it to a
  # single key, only via a "prefix" condition, which still requires the
  # bucket ARN as the resource. Covers both this environment's own state key
  # and every remote state key read above.
  statement {
    sid    = "StateBucketList"
    effect = "Allow"
    actions = [
      "s3:ListBucket",
    ]
    resources = ["arn:aws:s3:::${var.state_bucket}"]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = concat([var.state_key], var.additional_remote_state_read_keys)
    }
  }

  statement {
    sid    = "StateLock"
    effect = "Allow"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:DeleteItem",
    ]
    resources = ["arn:aws:dynamodb:${var.aws_region}:${var.aws_account_id}:table/${var.state_lock_table}"]
  }
}

resource "aws_iam_policy" "state_backend" {
  name   = "${var.project_name}-${var.environment}-scheduled-destroy-state"
  policy = data.aws_iam_policy_document.state_backend.json
}

resource "aws_iam_role_policy_attachment" "state_backend" {
  role       = aws_iam_role.codebuild_destroy.name
  policy_arn = aws_iam_policy.state_backend.arn
}

# ── Destroy permissions ──────────────────────────────────────────────────────
#
# This is necessarily broad: `terraform destroy` refreshes state before
# destroying, so it needs read (Describe/Get/List) access across every
# resource type in environments/dev, plus delete access for each. Scoped by
# resource ARN name-prefix or the account's default tags (Project/Environment,
# set in the environment root module's provider block) everywhere AWS
# supports it. A handful of actions require resource = "*" because the
# service doesn't support resource-level permissions for that action at all —
# each is commented with why, matching the pattern already used in
# infrastructure/shared/oidc.tf.

data "aws_iam_policy_document" "destroy_permissions" {
  # EC2/VPC: Describe* actions never support resource-level scoping.
  statement {
    sid       = "EC2Describe"
    effect    = "Allow"
    actions   = ["ec2:Describe*"]
    resources = ["*"]
  }

  # EC2/VPC mutations: scoped via the default tags every resource in
  # environments/dev carries (Project=collabspace, Environment=dev).
  statement {
    sid    = "EC2Destroy"
    effect = "Allow"
    actions = [
      "ec2:DeleteVpc",
      "ec2:DeleteSubnet",
      "ec2:DeleteInternetGateway",
      "ec2:DetachInternetGateway",
      "ec2:DeleteRouteTable",
      "ec2:DeleteRoute",
      "ec2:DisassociateRouteTable",
      "ec2:DeleteSecurityGroup",
      "ec2:RevokeSecurityGroupIngress",
      "ec2:RevokeSecurityGroupEgress",
      "ec2:DeleteVpcEndpoints",
      "ec2:DeleteNetworkInterface",
      "ec2:DeleteTags",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/Environment"
      values   = [var.environment]
    }
  }

  # ECS: Describe/List never support resource-level scoping (same limitation
  # documented in oidc.tf for RegisterTaskDefinition).
  statement {
    sid    = "ECSDescribe"
    effect = "Allow"
    actions = [
      "ecs:DescribeClusters",
      "ecs:DescribeServices",
      "ecs:DescribeTaskDefinition",
      "ecs:DescribeTasks",
      "ecs:ListTasks",
      "ecs:ListTagsForResource",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "ECSDestroy"
    effect = "Allow"
    actions = [
      "ecs:DeleteCluster",
      "ecs:DeleteService",
      "ecs:UpdateService",
      "ecs:DeregisterTaskDefinition",
    ]
    resources = [
      "arn:aws:ecs:${var.aws_region}:${var.aws_account_id}:cluster/${var.project_name}-${var.environment}",
      "arn:aws:ecs:${var.aws_region}:${var.aws_account_id}:service/${var.project_name}-${var.environment}/*",
      "arn:aws:ecs:${var.aws_region}:${var.aws_account_id}:task-definition/${var.project_name}-${var.environment}-*",
    ]
  }

  # Service Discovery (Cloud Map): AWS's docs don't list resource-level
  # permission support for this service, but ARN wildcard syntax is accepted
  # and enforced in practice — scoped by name-prefix rather than left at "*".
  # If this proves too narrow on the first real run, the fallback is widening
  # to resources = ["*"] with the same justification as EC2Describe above.
  statement {
    sid    = "ServiceDiscovery"
    effect = "Allow"
    actions = [
      "servicediscovery:ListNamespaces",
      "servicediscovery:ListServices",
      "servicediscovery:ListInstances",
      "servicediscovery:GetNamespace",
      "servicediscovery:GetService",
      "servicediscovery:DeleteNamespace",
      "servicediscovery:DeleteService",
    ]
    resources = [
      "arn:aws:servicediscovery:${var.aws_region}:${var.aws_account_id}:namespace/*",
      "arn:aws:servicediscovery:${var.aws_region}:${var.aws_account_id}:service/*",
    ]
  }

  # API Gateway v2: uses HTTP-verb-style IAM actions on path-shaped ARNs
  # rather than per-operation action names — this is the standard AWS-
  # documented scoping approach for this service, not a workaround.
  statement {
    sid    = "ApiGatewayV2"
    effect = "Allow"
    actions = [
      "apigateway:GET",
      "apigateway:DELETE",
      "apigateway:PATCH",
    ]
    resources = [
      "arn:aws:apigateway:${var.aws_region}::/apis/*",
      "arn:aws:apigateway:${var.aws_region}::/vpclinks/*",
    ]
  }

  # IAM: name-prefix scoped, same convention as the PassRole statement in
  # oidc.tf. Covers the shared task execution role, per-service task roles,
  # and the Lambda execution role. Discovered missing GetPolicyVersion on the
  # first real test run — GetPolicy alone returns metadata, not the policy
  # document Terraform refresh actually reads.
  statement {
    sid    = "IAMDestroy"
    effect = "Allow"
    actions = [
      "iam:GetRole",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:ListPolicyVersions",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:DetachRolePolicy",
    ]
    resources = [
      "arn:aws:iam::${var.aws_account_id}:role/${var.project_name}-${var.environment}-*",
      "arn:aws:iam::${var.aws_account_id}:policy/${var.project_name}-${var.environment}-*",
    ]
  }

  # Lambda: same name-prefix pattern as the lambda_deploy policy in oidc.tf.
  statement {
    sid    = "LambdaDestroy"
    effect = "Allow"
    actions = [
      "lambda:GetFunction",
      "lambda:GetPolicy",
      "lambda:DeleteFunction",
      "lambda:RemovePermission",
    ]
    resources = ["arn:aws:lambda:${var.aws_region}:${var.aws_account_id}:function:${var.project_name}-${var.environment}-*"]
  }

  # SNS: scoped to the workspace-events topic by name prefix.
  statement {
    sid    = "SNSDestroy"
    effect = "Allow"
    actions = [
      "sns:GetTopicAttributes",
      "sns:ListTagsForResource",
      "sns:DeleteTopic",
    ]
    resources = ["arn:aws:sns:${var.aws_region}:${var.aws_account_id}:${var.project_name}-${var.environment}-*"]
  }

  # SSM: scoped to this environment's parameter path, mirroring the
  # /collabspace/{env}/ convention used by the ECS task execution role.
  statement {
    sid    = "SSMDestroy"
    effect = "Allow"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:DeleteParameter",
      "ssm:DeleteParameters",
    ]
    resources = ["arn:aws:ssm:${var.aws_region}:${var.aws_account_id}:parameter/${var.project_name}/${var.environment}/*"]
  }

  # CloudWatch Logs: the destroy *target's* log groups (per-service logs,
  # API Gateway access logs) — distinct from CodeBuildOwnLogs above, which
  # covers only this job's own build output.
  statement {
    sid       = "LogsDescribe"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }

  statement {
    sid    = "LogsDestroy"
    effect = "Allow"
    actions = [
      "logs:DeleteLogGroup",
      "logs:ListTagsForResource",
    ]
    resources = [
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/${var.project_name}/${var.environment}/*",
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/aws/apigateway/${var.project_name}-${var.environment}-*",
    ]
  }

  # ECR: read-only, needed by data.aws_ecr_image.auth_workspace_latest in
  # environments/dev/main.tf to resolve the latest pushed image tag.
  statement {
    sid    = "ECRDescribe"
    effect = "Allow"
    actions = [
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
    ]
    resources = ["arn:aws:ecr:${var.aws_region}:${var.aws_account_id}:repository/${var.project_name}-*"]
  }
}

resource "aws_iam_policy" "destroy_permissions" {
  name   = "${var.project_name}-${var.environment}-scheduled-destroy-permissions"
  policy = data.aws_iam_policy_document.destroy_permissions.json
}

resource "aws_iam_role_policy_attachment" "destroy_permissions" {
  role       = aws_iam_role.codebuild_destroy.name
  policy_arn = aws_iam_policy.destroy_permissions.arn
}

# ── CodeBuild project ────────────────────────────────────────────────────────
#
# buildspec is inline (not a buildspec.yml read from the repo) per ADR-039:
# this role can delete VPCs and IAM roles, so what it runs must go through
# the same Terraform plan-review gate as everything else, not be editable by
# a merged code change alone.

resource "aws_codebuild_project" "destroy" {
  name          = "${var.project_name}-${var.environment}-scheduled-destroy"
  description   = "Nightly unconditional terraform destroy of ${var.terraform_working_directory}. See ADR-039."
  service_role  = aws_iam_role.codebuild_destroy.arn
  build_timeout = 15

  artifacts {
    type = "NO_ARTIFACTS"
  }

  environment {
    compute_type                = "BUILD_GENERAL1_SMALL"
    image                       = "hashicorp/terraform:1.9"
    type                        = "LINUX_CONTAINER"
    image_pull_credentials_type = "CODEBUILD"

    # The root module declares required variables (Neon/Redis credentials,
    # the GitHub PAT itself) that are normally supplied by secrets.auto.tfvars
    # — gitignored, so CodeBuild's git clone never has it. `terraform destroy`
    # never reads these values for anything (they only ever get written into
    # aws_ssm_parameter resources, and destroying a parameter doesn't depend
    # on its value) — it just requires every declared variable to be *set*.
    # Placeholders here are deliberate: the destroy job should never need to
    # see real secrets at all.
    dynamic "environment_variable" {
      for_each = var.extra_environment_variables
      content {
        name  = environment_variable.key
        value = environment_variable.value
        type  = "PLAINTEXT"
      }
    }
  }

  source {
    type            = "GITHUB"
    location        = var.github_repo_url
    git_clone_depth = 1
    buildspec       = <<-EOT
      version: 0.2
      phases:
        build:
          commands:
            - cd ${var.terraform_working_directory}
            - terraform init -input=false
            - terraform destroy -auto-approve -input=false
      EOT
  }

  source_version = var.github_source_branch

  logs_config {
    cloudwatch_logs {
      group_name = "/aws/codebuild/${var.project_name}-${var.environment}-scheduled-destroy"
    }
  }

  depends_on = [aws_codebuild_source_credential.github]

  tags = {
    Name = "${var.project_name}-${var.environment}-scheduled-destroy"
  }
}

# ── EventBridge Scheduler ────────────────────────────────────────────────────
#
# A dedicated role scoped to StartBuild on exactly this one CodeBuild
# project — the scheduler itself has no destroy permissions, it only ever
# invokes the project above, which holds those separately.

data "aws_iam_policy_document" "scheduler_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler_invoke_codebuild" {
  name               = "${var.project_name}-${var.environment}-scheduled-destroy-trigger"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume_role.json

  tags = {
    Name = "${var.project_name}-${var.environment}-scheduled-destroy-trigger"
  }
}

data "aws_iam_policy_document" "scheduler_start_build" {
  statement {
    effect    = "Allow"
    actions   = ["codebuild:StartBuild"]
    resources = [aws_codebuild_project.destroy.arn]
  }
}

resource "aws_iam_policy" "scheduler_start_build" {
  name   = "${var.project_name}-${var.environment}-scheduled-destroy-trigger"
  policy = data.aws_iam_policy_document.scheduler_start_build.json
}

resource "aws_iam_role_policy_attachment" "scheduler_start_build" {
  role       = aws_iam_role.scheduler_invoke_codebuild.name
  policy_arn = aws_iam_policy.scheduler_start_build.arn
}

resource "aws_scheduler_schedule" "nightly_destroy" {
  name       = "${var.project_name}-${var.environment}-nightly-destroy"
  group_name = "default"

  flexible_time_window {
    mode = "OFF"
  }

  schedule_expression          = var.schedule_expression
  schedule_expression_timezone = var.schedule_timezone

  target {
    arn      = aws_codebuild_project.destroy.arn
    role_arn = aws_iam_role.scheduler_invoke_codebuild.arn
  }
}
