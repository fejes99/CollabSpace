output "function_name" {
  description = "Lambda function name. Used in CI/CD workflows (aws lambda update-function-code --function-name <name>) and in the API Gateway Lambda permission resource."
  value       = aws_lambda_function.function.function_name
}

output "function_arn" {
  description = "Lambda function ARN. Used when granting other services permission to invoke this function."
  value       = aws_lambda_function.function.arn
}

output "invoke_arn" {
  description = "Lambda invocation ARN. Used as integration_uri in aws_apigatewayv2_integration when wiring API Gateway → Lambda."
  value       = aws_lambda_function.function.invoke_arn
}

output "execution_role_arn" {
  description = "ARN of the Lambda execution IAM role. Attach additional policies here when the function needs access to other AWS services (e.g., SES, SNS) in Stage 2+."
  value       = aws_iam_role.lambda_execution.arn
}
