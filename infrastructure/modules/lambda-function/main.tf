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
# AWSLambdaBasicExecutionRole is the minimum policy needed: CloudWatch log
# group creation, stream creation, and log event publishing. Application-level
# AWS access (SES for email, SNS for push) should be added as separate inline
# or managed policies on this same role — not by broadening the execution role.

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
# timeout: API Gateway HTTP API has a 30-second integration timeout. This
# must be less than or equal to that limit.
#
# logging_config.log_group: wires the function to the pre-tagged log group
# created by the cloudwatch module. Without this, Lambda auto-creates an
# untagged group with infinite retention on first invocation.

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
    ignore_changes = [filename, source_code_hash]
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-${var.service_name}"
    Service = var.service_name
  }
}
