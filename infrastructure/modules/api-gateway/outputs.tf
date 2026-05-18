output "api_id" {
  description = "API Gateway HTTP API ID. Used when creating integrations and routes in the calling module."
  value       = aws_apigatewayv2_api.main.id
}

output "api_endpoint" {
  description = "Public HTTPS endpoint of the API Gateway (e.g. https://{id}.execute-api.eu-central-1.amazonaws.com). This is the URL clients use to reach all services."
  value       = aws_apigatewayv2_api.main.api_endpoint
}

output "api_execution_arn" {
  description = "Execution ARN used in Lambda resource-based policies. Format: arn:aws:execute-api:{region}:{account}:{api_id}. Append /*/*  to allow any method on any route."
  value       = aws_apigatewayv2_api.main.execution_arn
}

output "vpc_link_id" {
  description = "VPC Link ID. Passed to aws_apigatewayv2_integration resources as connection_id when using VPC_LINK connection type."
  value       = aws_apigatewayv2_vpc_link.main.id
}

output "jwks_uri" {
  description = "JWKS URI constructed from the API Gateway endpoint. Written to SSM so auth-workspace knows the URL where it must serve its public keys."
  value       = "${aws_apigatewayv2_api.main.api_endpoint}/.well-known/jwks.json"
}
