# ADR-035: Paired Exact-Path and Proxy Routes for API Gateway HTTP API Resources

**Status:** Accepted
**Date:** 2026-07-15

---

## Context

API Gateway HTTP API's greedy path variable `{proxy+}` requires at least one path segment after the prefix it's attached to — it cannot match the bare collection path with zero segments. Every JWT-protected resource route in `infrastructure/environments/dev/main.tf` (added incrementally, starting with ADR-026's ALB→API Gateway migration in PR #11) was defined only as `ANY /v1/<resource>/{proxy+}`: `auth_proxy`, `workspaces_proxy`, `realtime_proxy`, `assistant_proxy`, `notifications_proxy`, `documents_proxy`.

PR #45 shipped `WorkspaceController` with `POST /v1/workspaces` — a bare collection-create endpoint, no trailing segment. A live smoke test against the deployed dev environment confirmed the request never reached auth-workspace: API Gateway returned its own `404 {"message":"Not Found"}` before the request left the routing layer, because no route matched the bare path.

Nothing about this was anticipated in ADR-026, which covers the ALB→API Gateway migration, JWT authorizer wiring, and the trust-header model, but never addresses `{proxy+}` path-matching semantics.

The gap is not workspace-specific. All five other resource routes share the identical shape, so any future bare-collection endpoint on any of them — most immediately `GET /v1/workspaces` (PR 10, list workspaces) — would hit the same wall.

---

## Decision

Every resource route gets a paired **exact bare-path route** alongside its existing `{proxy+}` route, targeting the same integration:

```
ANY /v1/workspaces          → auth_workspace_protected   (new)
ANY /v1/workspaces/{proxy+} → auth_workspace_protected   (existing)
```

Applied to all six JWT-protected resource routes now — `auth`, `workspaces`, `realtime`, `assistant`, `notifications`, `documents` — not only the one PR #45 needs today. `auth`, `realtime`, `assistant`, `notifications`, and `documents` have no bare-collection endpoint yet, but pairing the routes now means the gap cannot resurface silently when one of those services eventually grows one; the alternative is re-discovering this exact failure mode service by service, each time via a live 404 rather than a design-time check.

Each new route is a plain hand-written `aws_apigatewayv2_route` resource, matching the file's existing convention (no `for_each`/module abstraction over routes) — six near-identical resources is not enough repetition to justify introducing a shared abstraction the rest of the file doesn't use.

---

## Alternatives considered

**One-off fix: add only `ANY /v1/workspaces`.** Rejected as the sole fix — it unblocks PR #45 today but leaves the same gap live for every other resource and for `GET /v1/workspaces` (PR 10) tomorrow. The failure mode (a live 404 discovered by smoke test, not caught by any plan or review) is exactly the kind of thing worth closing everywhere at once, once it's understood.

**Change the `{proxy+}` route keys to something that also matches zero segments.** Not possible — HTTP API's greedy path variable syntax has no "zero-or-more" form; `{proxy+}` is hard-coded by AWS to "one or more". Pairing two explicit routes is the only way to express "this path prefix, with or without a trailing segment" in this API type.

**Introduce a `for_each`-driven local map to generate both routes per resource.** Would reduce repetition (six pairs → one loop), but every other section of this file (ECS service modules, integrations) is still hand-written per resource, so a routes-only abstraction would be the one inconsistent pattern in the file. Revisit if a seventh resource is added and the duplication becomes harder to keep in sync by hand.

---

## Consequences

**Positive:**
+ `POST /v1/workspaces` (PR #45) is reachable through API Gateway; the smoke-test 404 is resolved at the routing layer, not worked around at the service layer
+ `GET /v1/workspaces` (PR 10, list) and any bare-collection endpoint on `auth`, `realtime`, `assistant`, `notifications`, or `documents` will work the first time they're deployed, with no separate infra PR required
+ The fix stays consistent with the file's existing all-hand-written-resources convention — no new abstraction to learn

**Negative:**
− Five of the six new routes (`auth`, `realtime`, `assistant`, `notifications`, `documents`) have no endpoint to serve yet — dead routes returning 404 from the service itself (not API Gateway) until those endpoints exist. Acceptable: an extra route costs nothing and the alternative is re-discovering this bug per service
− Twelve total resource blocks per JWT-protected integration now, instead of six — the file grows, and a developer adding a seventh resource must remember to add both routes, not just one, since nothing enforces the pairing structurally
− Does not address whether *other* HTTP methods on a bare collection path (e.g. a future `DELETE /v1/workspaces` bulk-delete) need different authorization than `POST` — the `ANY` route key grants the same JWT-required treatment to every method on the bare path, same as the existing `{proxy+}` routes already do

---

## Revisit when

- A seventh JWT-protected resource is added — reconsider the `for_each` alternative once keeping six-plus pairs in sync by hand becomes error-prone
- Any bare-collection route needs per-method authorization different from `ANY` (e.g. `GET /v1/workspaces` public but `POST /v1/workspaces` admin-only within some future workspace) — the paired-route pattern would need to split into per-method routes at that point
- AWS adds a "zero-or-more segments" greedy path syntax to HTTP API — this ADR's workaround becomes unnecessary
