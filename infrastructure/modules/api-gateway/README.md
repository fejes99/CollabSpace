# api-gateway module

Creates the **API Gateway HTTP API** that serves as the REST entry point for all CollabSpace services. See [ADR-026](../../../docs/06-decisions/adr-026-api-gateway-rest-entry.md) for the rationale.

## What it creates

| Resource | Notes |
|---|---|
| `aws_apigatewayv2_api` | HTTP API (v2). CORS configured for dev (wildcard origin). |
| `aws_apigatewayv2_vpc_link` | Routes traffic from API Gateway into the VPC. No hourly charge for HTTP API VPC Links. |
| `aws_apigatewayv2_stage` | `$default` stage with `auto_deploy = true`. Stage variable `internalToken` injected into every forwarded request as `X-Internal-Token`. |
| `aws_cloudwatch_log_group` | API Gateway access logs at `/aws/apigateway/{project}-{env}`. 7-day retention by default. |

**Not created here:** The JWT Authorizer is intentionally defined in the calling environment (`environments/dev/main.tf`) rather than this module. AWS validates the OIDC discovery endpoint at authorizer creation time, which requires auth-workspace to be running — a constraint that cannot be met inside the module. See [ADR-029](../../../docs/06-decisions/adr-029-jwt-authorizer-cold-start.md).

## Usage

```hcl
module "api_gateway" {
  source = "../../modules/api-gateway"

  project_name       = var.project_name
  environment        = var.environment
  vpc_link_sg_id     = module.security_groups.vpc_link_sg_id
  subnet_ids         = module.vpc.public_subnet_ids
  internal_token     = random_password.internal_token.result
  log_retention_days = 7
}
```

After calling the module, define integrations and routes in the calling environment using `module.api_gateway.api_id` and `module.api_gateway.vpc_link_id`. The JWT Authorizer is also defined there; its `id` is passed into protected routes as `authorizer_id`.

## Inputs

| Name | Type | Required | Description |
|---|---|---|---|
| `project_name` | `string` | yes | Short identifier used as a prefix on all resource names. |
| `environment` | `string` | yes | Deployment environment (`dev`, `staging`, `prod`). |
| `vpc_link_sg_id` | `string` | yes | Security group attached to VPC Link ENIs. Must allow outbound TCP to the ECS tasks security group. |
| `subnet_ids` | `list(string)` | yes | Subnets where VPC Link ENIs are placed. Must be in the same VPC as ECS tasks. |
| `internal_token` | `string` | yes | Shared secret injected as `X-Internal-Token` on every forwarded request. Mark sensitive in the caller. |
| `log_retention_days` | `number` | no | CloudWatch log retention in days. Default: `7`. |

## Outputs

| Name | Description |
|---|---|
| `api_id` | HTTP API ID. Pass to `aws_apigatewayv2_integration` and `aws_apigatewayv2_route` resources. |
| `api_endpoint` | Public HTTPS endpoint (`https://{id}.execute-api.{region}.amazonaws.com`). |
| `api_execution_arn` | Execution ARN for Lambda resource-based policies (`arn:aws:execute-api:...`). |
| `vpc_link_id` | VPC Link ID. Pass as `connection_id` to `HTTP_PROXY` integrations. |
| `jwks_uri` | JWKS URI derived from the API endpoint (`{api_endpoint}/.well-known/jwks.json`). Write to SSM so auth-workspace can serve public keys at the correct URL. |

## Why `auto_deploy = true`

Changes to routes and integrations take effect immediately without a manual deployment step. This is appropriate for dev where fast iteration matters. In staging/prod, set `auto_deploy = false` and manage deployments explicitly to control rollout timing.

## Why the VPC Link is in public subnets

ECS tasks run in public subnets (ADR-009). The VPC Link ENIs must be in the same network tier to route to task IPs. Placing ENIs in private subnets while tasks are in public subnets would require a NAT Gateway, adding ~$32/month. See ADR-009 for the full reasoning.
