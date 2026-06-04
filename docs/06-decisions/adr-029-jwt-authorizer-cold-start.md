# ADR-029: JWT Authorizer Defined Outside the api-gateway Module

**Status:** Accepted
**Date:** 2026-06-04

---

## Context

The `api-gateway` Terraform module originally created the `aws_apigatewayv2_authorizer` resource alongside the HTTP API, VPC Link, and default stage. This was a natural grouping — all API Gateway infrastructure in one module.

When the dev environment is fully torn down (`make dev-down`) and recreated from scratch (`make dev-up`), all resources are created simultaneously. This exposed a bootstrapping problem.

**How AWS creates a JWT Authorizer.** When Terraform calls `CreateAuthorizer` on the API Gateway v2 API, AWS immediately fetches `{issuer}/.well-known/openid-configuration` to discover the JWKS URI. The issuer is set to the API Gateway endpoint itself (see ADR-026), so AWS fetches `{api_endpoint}/.well-known/openid-configuration`.

**Why this fails on fresh creation.** The `GET /.well-known/openid-configuration` route is defined in `environments/dev/main.tf`, outside the module. When Terraform creates the module, routes have not been defined yet. Even after routes are created, the auth-workspace ECS task must be running and healthy to serve the response. On a fresh `dev-up` starting from nothing, auth-workspace is not yet running when the module's resources are being created.

The result: `BadRequestException: Invalid issuer … Issuer must have a valid discovery endpoint`. Terraform exits with an error and the JWT Authorizer, plus all six protected routes that depend on it, are not created.

This issue was latent — it was never hit before because PR #17 added the JWT Authorizer to a running environment where auth-workspace was already healthy.

---

## Decision

Move `aws_apigatewayv2_authorizer` out of the `api-gateway` module and into `environments/dev/main.tf` as a top-level resource.

Add a `terraform_data` resource that polls the OIDC discovery endpoint before the authorizer is created:

```hcl
resource "terraform_data" "wait_for_oidc" {
  depends_on       = [aws_apigatewayv2_route.auth_oidc_discovery, module.auth_workspace]
  triggers_replace = [module.api_gateway.api_endpoint]

  provisioner "local-exec" {
    command = <<-EOT
      ENDPOINT="${module.api_gateway.api_endpoint}/.well-known/openid-configuration"
      for i in $(seq 1 30); do
        curl -sf "$ENDPOINT" > /dev/null 2>&1 && exit 0
        sleep 10
      done
      exit 1
    EOT
  }
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  depends_on = [terraform_data.wait_for_oidc]
  ...
}
```

The `triggers_replace` on the API endpoint means the wait loop re-runs whenever a new API Gateway is created (i.e., on every fresh `dev-up`). On incremental applies where the API Gateway already exists, the loop is skipped.

The `api-gateway` module loses the `jwt_audience` variable and `authorizer_id` output. All six protected routes in `main.tf` now reference `aws_apigatewayv2_authorizer.jwt.id` directly.

---

## Why This Works

The `depends_on = [module.auth_workspace]` ensures Terraform creates the ECS service before starting the poll loop. Because the ECS service module has `lifecycle { ignore_changes = [task_definition] }`, subsequent `dev-up` runs do not redeploy auth-workspace — auth-workspace is already running from the previous apply or from CI/CD. The wait loop only blocks when all of the following are true simultaneously:

1. The API Gateway endpoint changed (new environment)
2. The `auth_oidc_discovery` route was just created
3. auth-workspace needs to start from scratch

On day-two and beyond, the loop is skipped entirely via `triggers_replace`.

---

## Alternatives considered

**Keep the authorizer in the module, deploy in two passes.** Run `terraform apply` once with the JWT Authorizer commented out, wait for auth-workspace to be healthy, then uncomment and apply again. This solves the bootstrapping problem but requires manual intervention on every `dev-down` / `dev-up` cycle. Not acceptable for a one-command lifecycle.

**Remove `depends_on` and increase retries.** Without `depends_on = [module.auth_workspace]`, the wait loop starts while ECS is still pulling the image. The ECS service module does not set `wait_for_steady_state = true`, so Terraform considers the service "created" before tasks are healthy. Simply increasing the retry count treats a symptom, not the cause.

**Add `wait_for_steady_state = true` to the ECS service module.** This would make the module wait until ECS tasks are healthy before Terraform considers it done, which is the correct fix for the underlying sequencing problem. However, it also means Terraform hangs if a deployment fails (the circuit breaker fires and rolls back, but Terraform still waits for steady state). For a dev environment where failed deployments happen (e.g., a bad image tag), this is a worse failure mode than the current approach. Deferred.

**Use a stable custom domain as the issuer.** If the issuer were `https://auth.dev.collabspace.io` (per ADR-026's design intent) rather than the API Gateway endpoint, the OIDC validation URL would be stable and could be pre-provisioned. This is the correct long-term approach for staging/prod. In dev, it requires a custom domain and TLS certificate, which exceed the $0–5/month budget constraint.

---

## Consequences

**Positive:**

- Fresh `dev-up` succeeds end-to-end without manual intervention.
- The wait loop makes the dependency explicit and visible rather than a hidden timing assumption.
- No cost impact.

**Negative:**

- The JWT Authorizer is no longer encapsulated in the api-gateway module. A caller must define it manually.
- The `api-gateway` module's `authorizer_id` output no longer exists. Callers reference `aws_apigatewayv2_authorizer.jwt.id` directly.
- The wait loop adds up to 5 minutes to a fresh `dev-up`. This is acceptable given the alternative (manual two-pass apply), but could be reduced by adding `wait_for_steady_state = true` to the ECS service module in a future iteration.

---

## Revisit when

- Moving to staging/prod: use a stable custom domain as the issuer (per ADR-026 intent) to eliminate the bootstrapping problem entirely at the architecture level.
- Adding `wait_for_steady_state = true` to the ECS service module is evaluated: if the stability trade-off is acceptable, the JWT Authorizer can move back into the module.
