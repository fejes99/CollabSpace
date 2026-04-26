output "state_bucket_name" {
  description = "S3 bucket name for Terraform state. Use this in the bucket field of all backend \"s3\" blocks."
  value       = aws_s3_bucket.terraform_state.bucket
}

output "lock_table_name" {
  description = "DynamoDB table name for state locking. Use this in the dynamodb_table field of all backend \"s3\" blocks."
  value       = aws_dynamodb_table.terraform_locks.name
}

output "aws_region" {
  description = "AWS region where bootstrap resources were created."
  value       = var.aws_region
}

output "aws_account_id" {
  description = "AWS account ID derived from the caller identity."
  value       = data.aws_caller_identity.current.account_id
}
