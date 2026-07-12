<!--
Title format: [full] / [small] / [fast] prefix + imperative sentence, ≤72 chars.

Examples:
  [full]  Add user registration endpoint to auth-workspace
  [small] Return 409 on duplicate email in /v1/auth/register
  [fast]  Bump Fastify from 5.0.0 to 5.0.1 in document-service

For [small] PRs: keep What, Why, Test plan. Drop items in Test plan that don't apply.
For [fast]  PRs: keep What and Why only. Delete the entire Test plan section.

Tier definitions and full DoD: docs/07-development/feature-workflow.md
-->

## What

One paragraph. What does this PR change?

## Why

One paragraph. Why is this change needed?

Plan: `docs/03-services/<service>/plans/<slug>.md`

## Test plan

- [ ] Happy-path integration test green against real DB
- [ ] Edge-case tests cover validation, authorization, conflict/not-found, observability
- [ ] Manual smoke run (command below)
- [ ] AWS smoke run (response pasted post-merge — see note below)
- [ ] OpenAPI spec updated and matches actual response shape
- [ ] Service README updated
- [ ] CLAUDE.md Layer 2 `Completed:` list updated
- [ ] ADR written if a non-obvious decision was made; cross-linked from the relevant plan doc/README
- [ ] Claude review addressed or explicitly accepted as-is (auto-runs on `ready_for_review`; see [ADR-024](../docs/06-decisions/adr-024-claude-pr-review.md))
- [ ] `/retrospect` run

### Manual smoke

```bash
# Paste the curl command used locally
```

### AWS response

<!--
AWS dev is a single shared environment (ADR-022) and only deploys on push to
main (service-auth.yml). This endpoint cannot be verified against AWS before
merge. Paste the exact bytes from the deployed endpoint here immediately after
merge, with timestamp, then check the box above.
-->
```
# Paste exact bytes from the deployed endpoint, with timestamp
```
