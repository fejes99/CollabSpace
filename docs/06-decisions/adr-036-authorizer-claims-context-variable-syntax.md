# ADR-036: Use `$context.authorizer.claims`, Not `$context.authorizer.jwt.claims`, in HTTP API Parameter Mapping

**Status:** Accepted
**Date:** 2026-07-15

---

## Context

`auth-workspace`'s API Gateway integration (`aws_apigatewayv2_integration.auth_workspace_protected`) maps JWT claims to identity headers for the backend service:

```hcl
request_parameters = {
  "overwrite:header.x-user-id"         = "$context.authorizer.jwt.claims.userId"
  "overwrite:header.x-user-workspaces" = "$context.authorizer.jwt.claims.memberships"
  "overwrite:header.x-jwt-jti"         = "$context.authorizer.jwt.claims.jti"
}
```

This was written in PR #41/#42 (security-filter) and looked correct — it matches the shape shown in numerous public AWS examples and blog posts. It was never live-verified: every deploy since then (`#41`, `#42`, `#43`, `#45`) failed for an unrelated reason (the dev environment was destroyed at merge time, per ADR-022, so the CI/CD workflow's "update ECS service" step had nothing to deploy to). Local integration tests inject `X-User-Id` directly and never touch API Gateway; there was no path by which this mapping was ever actually exercised before today.

While live-verifying PR #45 (`POST /v1/workspaces`) for the first time — after fixing an unrelated routing gap (ADR-035) — the request reached the service but `HeaderAuthenticationFilter` rejected it with `401 X-User-Id is required on this route`. Diagnosis:

1. Added debug fields to the API Gateway access log format (`$context.authorizer.jwt.claims.userId`, `.sub`) — both rendered as `-` (API Gateway's empty-value placeholder), for a request that had demonstrably passed the JWT authorizer (it reached the service; a failed authorizer returns API Gateway's own generic `401 {"message":"Unauthorized"}` before ever reaching the integration).
2. Temporarily hardcoded `x-user-id` to a static string (not derived from `$context` at all) — it arrived correctly (the service's error changed from "X-User-Id is required" to "X-User-Id and X-User-Workspaces must be present or absent together"), proving the `overwrite:header` mapping mechanism itself works fine on this integration.
3. This isolated the fault to the specific expression `$context.authorizer.jwt.claims.<property>`, independent of which claim name was used (`sub`, a claim guaranteed present on every valid token, failed identically to the custom `userId` claim).
4. AWS's own [HTTP API logging/parameter-mapping context variable reference](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-logging-variables.html) — the authoritative list of `$context.*` variables usable in both access log formats and `request_parameters` — documents the correct variable as **`$context.authorizer.claims.{property}`**, with no `.jwt.` segment.

The `.jwt.claims.` form does exist, but it belongs to a different, unrelated code path: it's the shape of the `event.requestContext.authorizer.jwt.claims` object delivered to a **Lambda proxy integration's event payload**, documented on AWS's [JWT authorizer page](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html). `auth-workspace`'s integration is `HTTP_PROXY` over a VPC Link, not a Lambda integration — there is no Lambda event, so that path was silently resolving to nothing. Nearly every public example that uses `.jwt.claims.` is written for the Lambda case, which makes the wrong syntax the more commonly-copied one.

## Decision

Use `$context.authorizer.claims.<property>` (no `.jwt.`) in every `request_parameters` mapping and access log format entry that reads a JWT authorizer claim on this project's HTTP APIs. Reserve `$context.authorizer.jwt.claims.<property>` / `event.requestContext.authorizer.jwt.claims.<property>` exclusively for code that reads a Lambda proxy integration's event payload directly — a case this project doesn't currently have for any JWT-authorized route, since `notification` (the only Lambda integration) sits behind a service-identity token, not a user JWT (ADR-014).

## Alternatives considered

**Switch to a Lambda authorizer that reads the JWT and sets an explicit `context` map.** Rejected — adds a Lambda invocation (cost, latency, cold start) to every authenticated request, purely to work around a syntax mistake. The native JWT authorizer already does full validation; only the claim-access variable name was wrong.

**Leave `.jwt.claims.` and have the service defensively treat missing identity headers as anonymous.** Rejected outright — this would silently disable authentication for every JWT-protected route rather than surfacing the bug, which is the opposite of `HeaderAuthenticationFilter`'s fail-closed design (`security-filter.md`).

## Consequences

**Positive:**
+ `X-User-Id`, `X-User-Workspaces`, and `X-JWT-Jti` now populate correctly for every request through a JWT-protected route — this mechanism was completely non-functional from the moment it was written (PR #41/#42) until this fix, across four merged PRs, without ever being caught
+ The fix is a two-line Terraform change (drop `.jwt` from three expressions), isolated to `infrastructure/environments/dev/main.tf` — no application code was wrong

**Negative:**
− No test in the current suite would have caught this class of bug. Testcontainers-based integration tests inject `X-User-Id` directly at the servlet layer, and local development bypasses API Gateway entirely (`INTERNAL_TOKEN` sent by hand, no authorizer in the loop) — nothing in the test pyramid exercises the actual API-Gateway-to-service header contract end-to-end
− The wrong syntax is easy to reintroduce: it's the form shown in most public AWS documentation and blog content, because most of that content is written for Lambda proxy integrations, and the distinction between "Lambda event payload" and "`$context.*` parameter mapping / access logs" isn't obvious unless you already know to look for it
− This bug was silent for roughly two weeks of merged work because every deploy in that window independently failed for an unrelated reason (ADR-022's destroy/apply cycle meant no deploy had a live target); the routing gap fixed in ADR-035 was masking this bug too, since requests never reached far enough to hit it until both were fixed in the same session

## Revisit when

- Considering a lightweight post-deploy smoke test (even a single authenticated round-trip) in the deploy workflow or `make dev-up` flow, specifically to catch API-Gateway-to-service contract bugs like this one automatically, instead of relying on manual live verification
- Adding any new `request_parameters` or access log field that reads an authorizer claim — copy this ADR's syntax, not a public example, unless the integration is confirmed to be a Lambda proxy integration
