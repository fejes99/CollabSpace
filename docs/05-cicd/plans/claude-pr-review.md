# Plan: Claude PR Review Pipeline

**Service:** infra (CI/CD)
**Slug:** claude-pr-review
**Tier:** Full
**Status:** Draft

## 1. Slice statement

Every PR opened as ready-for-review receives a Claude code review comment.

## 2. User-visible behavior

- Within a couple of minutes of setting a PR from draft to ready, a single comment appears with the review.
- Posting a comment containing `@claude` on a PR triggers a code review.
- PRs whose titles start with `[fast]` (case-insensitive) do not trigger a review.

## 3. Workflow contract

**Triggers**

- `pull_request: types: [opened, ready_for_review]` with `if: !github.event.pull_request.draft`
- `issue_comment: types: [created]` with multi-guard condition (see Skip)

**Skip conditions (job-level `if:`)**

- PR title prefix `[fast]` (case-insensitive)
- PR from fork (`github.event.pull_request.head.repo.fork == true`)
- For `@claude` trigger: PR state is not `open`
- For `@claude` trigger: comment author type is `Bot`
- For `@claude` trigger: comment author login is `github-actions[bot]` (self-loop guard)
- For `@claude` trigger: `comment.author_association != 'OWNER'`
- Oversized PR — `changed_files > 100` OR `additions + deletions > 5000` — workflow posts a polite skip comment and exits

**Concurrency**

- `group: claude-review-${{ github.event.pull_request.number || github.event.issue.number }}`
- `cancel-in-progress: true`

**Permissions**

- `contents: read`
- `pull-requests: write`
- `issues: write`

**Cost caps**

- `timeout-minutes: 10`
- Action input `max_tokens: 12000`

**Action**

- `anthropics/claude-code-action@86eb26bf0139bdd75acd15ea5f00f45ee0a284c2` (`v1.0.122`) — SHA-pinned; rotation cadence in [ADR-024](../../06-decisions/adr-024-claude-pr-review.md)
- Resolve a new SHA via `git ls-remote https://github.com/anthropics/claude-code-action.git refs/tags/v1` and take the line ending in `refs/tags/v1^{}`
- Model: `claude-sonnet-4-6`
- Reads `secrets.ANTHROPIC_API_KEY`

## 4. Data model changes

Not applicable. No schema, no database.

## 5. Validation rules

Folded into §3. All input validation is event-filter logic in workflow `if:` clauses.

## 6. Edge cases

| Scenario | Behavior |
|---|---|
| Draft PR (no @claude) | Workflow does not run (event filter) |
| Draft PR + `@claude` comment | Workflow exits early — draft guard on comment job |
| Fork PR | Exit early with log: "Fork PR — review skipped" |
| `[fast]` / `[FAST]` / `[Fast]` PR title | Exit early |
| Closed or merged PR + `@claude` | Exit early |
| Bot-authored `@claude` comment | Exit early |
| `github-actions[bot]` self-comment | Exit early |
| Non-`OWNER` commenter triggering `@claude` | Exit early |
| Oversized PR (>100 files OR >5000 lines) | Exit early; post a comment explaining why |
| `ANTHROPIC_API_KEY` missing | Action fails; workflow red; no comment posted |
| Anthropic API 5xx or rate-limited | Action retries; on final failure, workflow red |
| Workflow `timeout-minutes` reached | Cancelled; partial cost incurred; no comment |
| Multiple `@claude` mentions in quick succession | Concurrency cancels the previous run; only the latest finishes |
| `@claude` quoted inside a reply | Still triggers (accepted; documented in ADR-024) |

## 7. Authorization

`pull_request` events follow GitHub's standard fork-secret isolation. `issue_comment` events do NOT — they run with full secret access regardless of commenter, which is a documented cost-attack vector if the repo is or becomes public.

Mitigation: the workflow's `@claude` job checks `github.event.comment.author_association` and only proceeds if the value is `OWNER`. Today that means only `fejes99` can trigger via `@claude`. Adding collaborators later requires an explicit decision — extend the allow-list to `MEMBER` / `COLLABORATOR` and accept that each collaborator can spend the budget, or keep `OWNER`-only and require re-trigger by the owner — recorded in a future ADR.

## 8. Observability

- **Workflow logs**: github.com/fejes99/CollabSpace/actions, per run. Show decision steps and the API call result.
- **Anthropic Console**: usage dashboard at console.anthropic.com. Per-call token spend.
- **PR comment trail**: each review is a comment in the PR thread.
- **Spend alarm**: $3 monthly threshold configured in the Anthropic Console (manual; not in repo code, since it is account-level config).

No correlation ID — this is CI, not a service request path.

## 9. Out of scope

- Auto-trigger on draft PRs (manual `@claude` on draft also blocked by the §3 guard).
- Auto-approval or auto-merge based on review (review is advisory only).
- Multi-model selection (Sonnet 4.6 only for v1).

## 10. Cross-document amendments included in this PR

- [docs/07-development/feature-workflow.md](../../07-development/feature-workflow.md) Phase 5 (Polish) — add a bullet describing the Claude review trigger.
- [.github/pull_request_template.md](../../../.github/pull_request_template.md) — add a test-plan checkbox: `[ ] Claude review addressed or explicitly accepted as-is`.
- [CLAUDE.md](../../../CLAUDE.md) Layer 3 — pointers for `.github/workflows/claude-review.yml` and the new ADR-024.

## 11. Open items to verify post-launch

- The action's behavior on duplicate review comments — does it edit the previous Claude comment or post a new one each time?
- The action's context-loading scope — how many tokens does it ingest beyond the diff?
- The action's exact input field names (`anthropic_api_key`, `model`, `max_tokens`) — verify against the action's current README before merge.
- SHA-pin rotation: scheduled every 3 months (next: 2026-08-14) or sooner on a disclosed CVE in `anthropics/claude-code-action`.
