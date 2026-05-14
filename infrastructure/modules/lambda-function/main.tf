terraform {
  required_providers {
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.0"
    }
  }
}

# ── Bootstrap placeholder ZIP ─────────────────────────────────────────────────
#
# Lambda requires code at creation time. This generates a minimal inline ZIP so
# Terraform can create the function on first apply without depending on a CI
# artifact. The CI/CD pipeline replaces this code on first deploy via
# `aws lambda update-function-code --zip-file`.
#
# ignore_changes on filename and source_code_hash (see lifecycle block below)
# ensures subsequent Terraform applies do not revert CI-deployed code back to
# this placeholder — the same pattern used for ECS task definitions (ADR-012).

data "archive_file" "placeholder" {
  type        = "zip"
  output_path = "${path.module}/bootstrap-placeholder.zip"

  source {
    content  = "export const handler = async () => ({ statusCode: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ status: 'ok' }) });"
    filename = "handler.mjs"
  }
}

# ── Lambda execution role ──────────────────────────────────────────────────────
#
# Lambda requires an IAM role it can assume. The execution role is distinct from
# the ECS task role pattern: Lambda has no long-running process, so the role is
# assumed at each invocation rather than at task start.
#
# AWSLambdaBasicExecutionRole is the minimum managed policy needed: it grants
# permission to create CloudWatch log groups, create log streams, and put log
# events. The function cannot write logs without it.
#
# Application-level AWS access (e.g., SES for email, SNS for push in Stage 2+)
# should be added as a separate inline or managed policy on this same role —
# not by broadening the execution role.

resource "aws_iam_role" "lambda_execution" {
  name = "${var.project_name}-${var.environment}-${var.service_name}-lambda"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "lambda.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}-lambda-role"
    Service = var.service_name
  }
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  role       = aws_iam_role.lambda_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# ── Lambda function ───────────────────────────────────────────────────────────
#
# runtime = nodejs24.x: matches the Node.js version used across the other
# Node.js services in this monorepo.
#
# handler: the entrypoint in the format "<filename-without-extension>.<export>".
# For an ESM module at dist/handler.mjs exporting `handler`, this is
# "handler.handler". The `.mjs` extension is implied by the ESM format — Lambda
# resolves it automatically when the function uses ESM.
#
# architectures = ["x86_64"]: matches the linux/amd64 build target in CI.
# Lambda Graviton (arm64) is ~20% cheaper but requires arm64 builds. Not worth
# the CI complexity at this scale.
#
# timeout / memory_size: 30 seconds and 128 MB are safe defaults for a
# notification handler. Lambda billing is duration × memory, so keeping memory
# low matters. Tune up if p99 latency or memory usage warrants it.
#
# logging_config: uses the Lambda-managed log group approach. The CloudWatch
# module creates the log group separately (so retention and tags are controlled
# centrally), and log_group is wired here to avoid Lambda auto-creating an
# untagged, infinite-retention log group.

resource "aws_lambda_function" "function" {
  function_name = "${var.project_name}-${var.environment}-${var.service_name}"
  role          = aws_iam_role.lambda_execution.arn
  runtime       = var.runtime
  handler       = var.handler
  architectures = ["x86_64"]
  timeout       = var.timeout
  memory_size   = var.memory_size

  filename         = data.archive_file.placeholder.output_path
  source_code_hash = data.archive_file.placeholder.output_base64sha256

  logging_config {
    log_format = "Text"
    log_group  = var.log_group_name
  }

  lifecycle {
    # CI/CD updates function code on every deploy. Terraform should not reset
    # the deployed code back to the placeholder on subsequent applies.
    # Same rationale as ignore_changes = [task_definition] in ecs-service (ADR-012).
    ignore_changes = [filename, source_code_hash]
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}

# ── ALB Lambda permission ─────────────────────────────────────────────────────
#
# Lambda has a resource-based policy separate from IAM. Even if the ALB has an
# IAM role that allows lambda:InvokeFunction, the Lambda function will reject
# the call unless its own resource-based policy allows it.
#
# source_arn scopes the permission to this specific target group. Without
# source_arn, any ALB in the account could invoke this function.

resource "aws_lambda_permission" "alb" {
  statement_id  = "AllowExecutionFromALB"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.function.function_name
  principal     = "elasticloadbalancing.amazonaws.com"
  source_arn    = aws_lb_target_group.lambda.arn
}

# ── ALB target group (Lambda) ─────────────────────────────────────────────────
#
# target_type = "lambda" is the key difference from the ECS target groups.
# Lambda target groups do not require vpc_id or port — the ALB invokes the
# function directly via the Lambda API, not over a network socket.
#
# Health checks: the ALB invokes the Lambda with a synthetic GET request to
# health_check_path and expects a 200 response. This means the function must
# handle health check events even during the bootstrap phase — the placeholder
# ZIP handles this by returning 200 for any invocation.
#
# lambda_multi_value_headers_enabled = false: the default. Enable in Stage 2+
# if the function needs to set multiple values for the same response header
# (e.g., Set-Cookie on multiple cookies).

resource "aws_lb_target_group" "lambda" {
  name        = substr("${var.project_name}-${var.environment}-${var.service_name}", 0, 32)
  target_type = "lambda"

  health_check {
    path                = var.health_check_path
    interval            = 35
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  lambda_multi_value_headers_enabled = false

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}-tg"
    Service = var.service_name
  }
}

# ── Target group attachment ───────────────────────────────────────────────────
#
# Registers the Lambda function as a target in the target group. The ALB cannot
# forward traffic to the function until this attachment exists.
#
# depends_on = [aws_lambda_permission.alb]: the ALB will immediately attempt a
# health check after the attachment is created. The health check triggers a
# Lambda invocation. If the Lambda permission does not exist yet, that invocation
# is rejected with AccessDenied and the target is marked unhealthy. Creating the
# permission first avoids this race condition.

resource "aws_lb_target_group_attachment" "lambda" {
  target_group_arn = aws_lb_target_group.lambda.arn
  target_id        = aws_lambda_function.function.arn
  depends_on       = [aws_lambda_permission.alb]
}

# ── ALB listener rule ─────────────────────────────────────────────────────────

resource "aws_lb_listener_rule" "lambda" {
  listener_arn = var.listener_arn
  priority     = var.listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.lambda.arn
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
