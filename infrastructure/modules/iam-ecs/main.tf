# ── Trust policy shared by both role types ──────────────────────────────────────
#
# A trust policy answers: "who is allowed to assume this role?"
# For ECS, the answer is the ECS Tasks service principal. This is how AWS
# knows to inject the role credentials into the running container.

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ── Task execution role (one, shared) ──────────────────────────────────────────
#
# Used by the ECS agent — not your application code.
# Needs: pull images from ECR, write logs to CloudWatch.
# AWS provides a managed policy (AmazonECSTaskExecutionRolePolicy) that covers
# exactly these two permissions. We attach it rather than duplicating the rules,
# so if AWS updates the managed policy we inherit the fix automatically.

resource "aws_iam_role" "task_execution" {
  name               = "${var.project_name}-${var.environment}-ecs-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name    = "${var.project_name}-${var.environment}-ecs-task-execution"
    Service = "ecs"
  }
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# ── SSM read policy (attached to execution role) ────────────────────────────────
#
# AmazonECSTaskExecutionRolePolicy does not include SSM access. ECS can inject
# SSM parameters as environment variables at task startup (via "secrets" in the
# task definition) — but only if the execution role can read them.
# We grant read access to the /collabspace/ prefix; each service only stores
# its own parameters under /collabspace/{service}/, so cross-service reads
# are not possible through this role.

data "aws_iam_policy_document" "ssm_read" {
  statement {
    sid    = "SSMGetParameters"
    effect = "Allow"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = [
      "arn:aws:ssm:${var.aws_region}:${var.aws_account_id}:parameter/collabspace/*",
    ]
  }

  # KMS decrypt is required when SSM parameters are stored as SecureString.
  # We use the default AWS-managed CMK (aws/ssm), which does not need an
  # explicit key ARN — access is granted via the key policy when the IAM
  # principal has ssm:GetParameter permission on the parameter.
  statement {
    sid     = "KMSDecryptSSM"
    effect  = "Allow"
    actions = ["kms:Decrypt"]
    resources = [
      "arn:aws:kms:${var.aws_region}:${var.aws_account_id}:key/alias/aws/ssm",
    ]
  }
}

resource "aws_iam_policy" "ssm_read" {
  name        = "${var.project_name}-${var.environment}-ssm-read"
  description = "Allows ECS task execution role to read SSM parameters under /collabspace/."
  policy      = data.aws_iam_policy_document.ssm_read.json

  tags = {
    Name = "${var.project_name}-${var.environment}-ssm-read"
  }
}

resource "aws_iam_role_policy_attachment" "task_execution_ssm" {
  role       = aws_iam_role.task_execution.name
  policy_arn = aws_iam_policy.ssm_read.arn
}

# ── Per-service task roles ──────────────────────────────────────────────────────
#
# Used by the application code running inside the container.
# One role per service so that permissions can diverge as services grow:
# ai-assistant may need S3 access, auth-workspace may need SES, etc.
#
# At this stage all task roles are empty (only the trust policy).
# That is intentional: an empty role that can be assumed is better than
# sharing a single role and having to decompose it later.

resource "aws_iam_role" "task" {
  for_each = var.services

  name               = "${var.project_name}-${var.environment}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Name    = "${var.project_name}-${var.environment}-${each.key}-task"
    Service = each.key
  }
}
