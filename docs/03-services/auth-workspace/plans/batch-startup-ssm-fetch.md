# Plan: batch-startup-ssm-fetch

**Tier:** [small]

## Slice statement

auth-workspace fetches its 5 startup SSM parameters (JWT private key, issuer, audience, jwks-uri, internal-token) via a single batched `ssm:GetParameters` call instead of 5 sequential `ssm:GetParameter` calls, bringing AWS dev cold-start back under the ECS health-check budget.

**Why:** In AWS dev, `Started AuthWorkspaceApplication` now logs at ~191s — past the 160s health-check budget (`health_check_start_period=150` + `interval*retries=10`) — so the ECS deployment circuit breaker kills the task before it ever passes a readiness check (confirmed: `failedTasks: 3`, `rolloutStateReason: "ECS deployment circuit breaker: tasks failed to start."`). Locally the same code path never runs at all — local dev mode uses `JWT_PRIVATE_KEY`/`INTERNAL_TOKEN` env vars directly, bypassing SSM entirely — so this was never caught before deploy. Each sequential `ssm:GetParameter` call is a blocking network round-trip on the main thread during bean construction; this PR's `InternalTokenSsmConfig` added the 5th such call on top of the 4 `JwtKeyConfig` already made.

## User-visible behavior

- auth-workspace starts successfully in AWS dev and passes its ECS health check within the configured start period.
- No change to any HTTP-facing behavior — purely internal startup wiring.
- If any of the 5 SSM parameters is missing, the app still fails fast at startup, now naming which parameter(s) are missing in one consolidated check instead of failing on whichever one happened to be fetched first.

## Startup behavior contract

- Trigger: Spring context refresh, gated on `JWT_PRIVATE_KEY_SSM_PATH` (existing condition, unchanged).
- Call: one `ssm:GetParameters` request for `[privateKeySsmPath, issuerSsmPath, audienceSsmPath, jwksUriSsmPath (if set), internalTokenSsmPath]`, `WithDecryption=true`.
- Success: values feed `JwtKeyConfig`'s and `InternalTokenSsmConfig`'s existing `@Bean` methods — no change to what those beans produce, only how the underlying values are fetched.
- Failure: if the response's `InvalidParameters` list is non-empty, fail startup immediately with an exception naming the missing path(s) — fail loud, never proceed with a null.
- jwks-uri is optional (falls back to `http://localhost:8080/.well-known/jwks.json`) — excluded from the batch request entirely when unset, not requested as a blank name.

## Validation rules

| Input | Constraint | Failure behavior |
|---|---|---|
| Each SSM path (`JWT_PRIVATE_KEY_SSM_PATH`, `JWT_ISSUER_SSM_PATH`, `JWT_AUDIENCE_SSM_PATH`, `INTERNAL_TOKEN_SSM_PATH`) | Must be non-blank (`StringUtils.hasText`) | Path omitted from the batch request; if it was required (all but jwks-uri), this surfaces via the missing-parameter check below |
| `JWT_JWKS_URI_SSM_PATH` | Optional | If blank, excluded from the batch request; bean falls back to the localhost default |
| Batch response `InvalidParameters` | Must be empty | `IllegalStateException` naming the missing path(s), thrown during bean construction — startup fails |

## Observability

- Existing `"JWT key loaded, kid=..."` log line preserved unchanged.
- New log line for the batch fetch itself: `event=ssm_batch_fetch_completed paths=<n>` — gives future cold-start regressions a visible signal in CloudWatch (this is exactly what would have caught this bug sooner).

## Out of scope

- `health_check_start_period` in Terraform — separate, already-identified fix; still worth doing as a backstop but a distinct infra decision from this one.
- `GetParametersByPath` or pagination — 5 params fits comfortably under `GetParameters`' 10-item cap; revisit if that grows.
- `LocalJwtConfig` / `LocalInternalTokenConfig` — local dev mode never calls SSM, unaffected by this change.
