# ADR-024: Claude PR Review Pipeline

## Status

Proposed

## Date

2026-05-14

## Context

CollabSpace is a learning project built by a single developer. There is no human peer reviewer. The Stage 2 feature workflow (see [feature-workflow.md](../07-development/feature-workflow.md)) prescribes adversarial plan review, PR self-review, and a feature retrospective — but the solo dynamic means the reviewer is also the author. The blind spots that survive that loop are the ones a fresh pair of eyes would catch.

A model-driven PR review can act as that fresh pair of eyes: independent of author intent, consistent across PRs, and available without scheduling a human. For a learning project, the additional value is pedagogical — Claude's review surfaces patterns and concerns the author might not have considered, accelerating skill development.

The cost dimension is real. The project has an explicit ~$5/month operational budget (free-tier maximalist per `CLAUDE.md`), and the Anthropic API balance available at this writing is approximately $4. Any automation that calls the paid API must have explicit per-run and per-month bounds, or it will eat the budget on a single bad day.

## Decision

Add a GitHub Action that invokes `anthropics/claude-code-action` on `issue_comment` events containing `@claude` from the repository `OWNER`. This is the only trigger — there is no automatic run on PR open or ready-for-review (revised 2026-07-15; the original decision did include that auto-trigger, see Alternatives). Use the `claude-sonnet-4-6` model, configured via `claude_args` (the action does not expose `model` as a top-level input). Skip `[fast]`-prefixed PRs (case-insensitive), fork PRs, draft PRs, PRs that are not open, and PRs exceeding 100 changed files or 5,000 line changes.

**Cost caps.** The action does not expose an output-token cap. The bounds in force are:
- `--max-turns 15` on `claude_args` — limits the agent loop iterations (each turn is one model call). Typical reviews complete in 3–5 turns.
- `timeout-minutes: 10` at the workflow level — wall-clock hard stop.
- `concurrency: cancel-in-progress: true` — successive triggers on the same PR cancel the previous run.

**Required permissions** include `id-token: write` (the action exchanges an OIDC token internally during setup). This is not currently documented in the action's README but is required by the action's source code.

The action is **SHA-pinned**, following the GitHub Actions security best practice flagged by our action linter. The current pin is `anthropics/claude-code-action@86eb26bf0139bdd75acd15ea5f00f45ee0a284c2` (`v1.0.122`, which is what `v1` currently dereferences to). Rotation cadence: every 3 months, or sooner if a CVE is disclosed in the upstream action. The workflow comment above the `uses:` line documents how to resolve a new SHA.

The `@claude` trigger is restricted to `comment.author_association == 'OWNER'`. This prevents cost-attack vectors that the `issue_comment` event's default permissions would otherwise allow if the repo were public.

Full design rationale and implementation details live in [docs/05-cicd/plans/claude-pr-review.md](../05-cicd/plans/claude-pr-review.md).

## Alternatives considered

- **Auto-trigger on PR open/ready-for-review, in addition to `@claude` comments.** This was the original decision. Reversed 2026-07-15: PR volume in Stage 2 makes a review on every ready-for-review PR material against the ~$5/month budget, and comment-only triggering lets the author choose which PRs are actually worth paying for. This knowingly re-accepts the discipline-burden risk the auto-trigger was originally meant to compensate for — see Consequences.
- **Auto-trigger on every PR push (every commit).** Maximum coverage. Rejected because solo development is iterative — pushing a fix every few minutes during edge-case work would burn the budget in a single afternoon.
- **Opus 4.7 for review.** ~5× cost of Sonnet. Reasonable if Sonnet starts missing things that matter; not the starting choice.
- **Haiku 4.5 for review.** ~1/5 cost but noticeably lower quality on complex review reasoning. Skipped — saving small amounts is bad math when output quality drops disproportionately.
- **Third-party LLM-review actions (e.g., `anc95/ChatGPT-CodeReviewer`, `presubmit-ai`).** Less Claude-native; slower upstream support for new Claude models. Skipped.
- **GitHub Copilot code review.** Different ecosystem. Anthropic's action is the right pairing with the Claude Code workflow already in use.
- **Self-hosted runner with custom Anthropic-SDK code.** Overkill at this scale; reimplements the official action with no added value.
- **No size guard.** Considered. Rejected because a single 50,000-line PR (e.g., a vendored dependency commit) could blow past the per-run cost estimate.
- **Allow-list of `MEMBER` / `COLLABORATOR` for `@claude`.** Considered. Rejected for v1 because the project is solo. Revisit when a collaborator joins.
- **Tag-pin (`@v1`) instead of SHA-pin.** Initially chosen for low friction — `@v1` receives upstream patches automatically and avoids manual rotation. Rejected after the action linter (correctly) flagged this as a supply-chain risk: a tag can be force-pushed to a malicious commit, and the workflow runs with a paid API key. The friction cost of quarterly SHA rotation is real but small compared to the downside of an undetected upstream compromise.

## Consequences

**+**

- Review cost is opt-in: the author decides which PRs are worth spending budget on, instead of every ready-for-review PR triggering a paid run automatically.
- Per-run cost is bounded by `--max-turns 15` (typical $0.15–$0.35) and `timeout-minutes: 10` (worst case ~$1.00 in a runaway-agent scenario, dominated by output tokens at $15/MTok).
- The `[fast]` skip, fork skip, and size guard prevent obvious cost-burn vectors.
- The `OWNER`-only `@claude` guard prevents cost attack via comments on a public repo.
- The action is SHA-pinned; the workflow's behavior cannot change without an explicit commit to update the pin.
- The pedagogical loop (write code → Claude review → reflect) accelerates learning.

**−**

- Monthly spend now depends on how often the author remembers to comment `@claude`, not on PR volume — comment-only triggering makes cost more predictable, but the cap is enforced by human discipline rather than an automatic mechanism.
- The action's output is advisory only — the workflow does not block merges based on review feedback. A junior author might ignore valid feedback if discipline slips.
- Comment-only triggering re-introduces the exact discipline burden the original auto-trigger alternative was rejected for: nothing stops a PR from merging without any model review if the author forgets to comment `@claude`. There is no CI enforcement of this step — feature-workflow.md Phase 6 documents it as a manual habit only.
- The `OWNER`-only restriction blocks future collaborators from triggering `@claude` until this ADR is revisited.
- SHA pinning means upstream patches (security, bug fixes) are NOT picked up automatically. The rotation cadence (every 3 months, or sooner on disclosed CVE) is the mitigation; if forgotten, the workflow runs on an older action than upstream considers current.
- The size guard (100 files / 5,000 lines) is a hard cutoff. Large legitimate PRs (e.g., a generated SDK update) will be skipped without review.
- The "any comment containing `@claude`" pattern is permissive — quoted text in a reply triggers a re-review. Accepted as low-frequency, low-cost noise rather than complicating with regex.
- Output is not capped at the model layer (the action does not expose `max_tokens`). The size guard (100 files / 5,000 lines) and `--max-turns 15` jointly bound exposure; an unreviewed-but-not-skipped large PR could in theory hit the timeout-minutes wall (~$1.00 worst case) before completing.

## Revisit when

- Monthly Anthropic spend exceeds $3 for two consecutive months — adjust cap, skip rules, or downgrade to manual-only.
- A review demonstrates Sonnet 4.6 missing things Opus 4.7 catches — try Opus on the next 3 PRs and measure cost vs. value.
- A collaborator joins the project — decide whether to extend the `@claude` allow-list and accept shared budget exposure.
- A genuine large PR is consistently skipped by the size guard — raise the threshold or add a manual override path.
- The 3-month SHA-rotation review — fetch `git ls-remote https://github.com/anthropics/claude-code-action.git refs/tags/v1`, compare to the current pin, update the workflow and this ADR together. The plan doc records the command.
- `anthropics/claude-code-action` releases a `v2` (breaking) — evaluate the upgrade alongside a rotation.
- This repo is made public — re-verify the `OWNER`-only check and consider tightening further (e.g., spend rate limiting).
- The Claude review feedback is consistently ignored by the author — review whether the integration is providing value or just generating noise.
- PRs are consistently merging without a `@claude` comment ever being posted — the discipline burden re-accepted in the 2026-07-15 revision is failing in practice; reconsider a lighter auto-trigger (e.g., once per PR, not on every draft→ready flip) as a middle ground.
