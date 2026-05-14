output "function_name" {
  description = "Lambda function name. Used in CI/CD workflows (aws lambda update-function-code --function-name <name>)."
  value       = aws_lambda_function.function.function_name
}

output "function_arn" {
  description = "Lambda function ARN. Used when granting other services permission to invoke this function."
  value       = aws_lambda_function.function.arn
}

output "invoke_arn" {
  description = "Lambda invocation ARN. Used as the integration URI when wiring API Gateway → Lambda in Stage 2+."
  value       = aws_lambda_function.function.invoke_arn
}

output "target_group_arn" {
  description = "ALB target group ARN. Referenced when creating CloudWatch alarms on Lambda error rates or response times."
  value       = aws_lb_target_group.lambda.arn
}

output "execution_role_arn" {
  description = "ARN of the Lambda execution IAM role. Attach additional policies here when the function needs access to other AWS services (e.g., SES, SNS) in Stage 2+."
  value       = aws_iam_role.lambda_execution.arn
}
