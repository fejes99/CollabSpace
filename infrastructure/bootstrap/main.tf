terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # No backend block — bootstrap uses local state by design.
  # See docs/06-decisions/adr-006-terraform-bootstrap-state.md
}

provider "aws" {
  region = var.aws_region
}

# CloudWatch billing metrics are only published in us-east-1 — this alias
# is used exclusively for the billing alarm and its SNS resources.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}

data "aws_caller_identity" "current" {}

# ── State bucket ──────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "terraform_state" {
  bucket = "${var.project_name}-terraform-state-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name        = "${var.project_name}-terraform-state"
    Environment = "global"
    Service     = "terraform-bootstrap"
    ManagedBy   = "terraform"
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── State lock table ──────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "terraform_locks" {
  name         = "${var.project_name}-terraform-locks"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name        = "${var.project_name}-terraform-locks"
    Environment = "global"
    Service     = "terraform-bootstrap"
    ManagedBy   = "terraform"
  }
}

# ── Billing alarm ─────────────────────────────────────────────────────────────

resource "aws_sns_topic" "billing_alarm" {
  provider = aws.us_east_1
  name     = "${var.project_name}-billing-alarm"

  tags = {
    Name        = "${var.project_name}-billing-alarm"
    Environment = "global"
    Service     = "terraform-bootstrap"
    ManagedBy   = "terraform"
  }
}

resource "aws_sns_topic_subscription" "billing_alarm_email" {
  provider  = aws.us_east_1
  topic_arn = aws_sns_topic.billing_alarm.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "billing" {
  provider            = aws.us_east_1
  alarm_name          = "${var.project_name}-monthly-spend-5usd"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 86400
  statistic           = "Maximum"
  threshold           = 5
  alarm_description   = "Monthly AWS estimated charges have reached $5 USD."
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.billing_alarm.arn]

  dimensions = {
    Currency = "USD"
  }

  tags = {
    Name        = "${var.project_name}-billing-alarm"
    Environment = "global"
    Service     = "terraform-bootstrap"
    ManagedBy   = "terraform"
  }
}
