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

Add a GitHub Action that invokes `anthropics/claude-code-action` on `pull_request` (`ready_for_review` and `opened` with a draft guard) and on `issue_comment` events containing `@claude` from the repository `OWNER`. Use the `claude-sonnet-4-6` model. Cap per-run output at 12,000 tokens; cap workflow runtime at 10 minutes; skip `[fast]`-prefixed PRs (case-insensitive), fork PRs, and PRs exceeding 100 changed files or 5,000 line changes.

The action is tag-pinned to `@v1`, not SHA-pinned. Upstream patches and minor releases within the `v1` line are accepted automatically; a breaking `v2` would require an explicit upgrade.

The `@claude` trigger is restricted to `comment.author_association == 'OWNER'`. This prevents cost-attack vectors that the `issue_comment` event's default permissions would otherwise allow if the repo were public.

Full design rationale and implementation details live in [docs/05-cicd/plans/claude-pr-review.md](../05-cicd/plans/claude-pr-review.md).

## Alternatives considered

- **`@claude` mention only, no auto-trigger.** Cheapest option. Rejected because the discipline burden of "remember to ask for review" falls on the author, and the author is the failure point this mechanism exists to compensate for.
- **Auto-trigger on every PR push (every commit).** Maximum coverage. Rejected because solo development is iterative — pushing a fix every few minutes during edge-case work would burn the budget in a single afternoon.
- **Opus 4.7 for review.** ~5× cost of Sonnet. Reasonable if Sonnet starts missing things that matter; not the starting choice.
- **Haiku 4.5 for review.** ~1/5 cost but noticeably lower quality on complex review reasoning. Skipped — saving small amounts is bad math when output quality drops disproportionately.
- **Third-party LLM-review actions (e.g., `anc95/ChatGPT-CodeReviewer`, `presubmit-ai`).** Less Claude-native; slower upstream support for new Claude models. Skipped.
- **GitHub Copilot code review.** Different ecosystem. Anthropic's action is the right pairing with the Claude Code workflow already in use.
- **Self-hosted runner with custom Anthropic-SDK code.** Overkill at this scale; reimplements the official action with no added value.
- **No size guard.** Considered. Rejected because a single 50,000-line PR (e.g., a vendored dependency commit) could blow past the per-run cost estimate.
- **Allow-list of `MEMBER` / `COLLABORATOR` for `@claude`.** Considered. Rejected for v1 because the project is solo. Revisit when a collaborator joins.
- **SHA-pin the action instead of tag-pin.** Considered. SHA pinning is the GitHub Actions security best practice — it prevents a supply-chain attack in which a tag is force-pushed to a malicious commit. Rejected for v1 because (a) the action is published by Anthropic, who is also the LLM provider and has direct interest in not compromising it, (b) SHA pins require manual rotation cadence, which is friction that does not pay off at this scale, and (c) tag pinning still receives upstream security patches within the major version without intervention. Revisit if the threat model changes (e.g., the repo becomes part of a wider supply chain).

## Consequences

**+**

- Every ready-for-review PR receives a fresh, model-driven review without scheduling a human.
- Per-run cost is bounded; worst case ~$0.50, typical $0.15–$0.25.
- The `[fast]` skip, fork skip, and size guard prevent obvious cost-burn vectors.
- The `OWNER`-only `@claude` guard prevents cost attack via comments on a public repo.
- The action is tag-pinned to `@v1`; upstream patches within the major version are picked up automatically.
- The pedagogical loop (write code → Claude review → reflect) accelerates learning.

**−**

- Monthly spend depends on PR volume. At current cost estimates, $4 buys 16–30 reviews; if Stage 2 generates more PRs than expected, the budget runs out before the month ends.
- The action's output is advisory only — the workflow does not block merges based on review feedback. A junior author might ignore valid feedback if discipline slips.
- The `OWNER`-only restriction blocks future collaborators from triggering `@claude` until this ADR is revisited.
- Tag pinning trusts the upstream not to force-push `v1` to a malicious commit. The action is Anthropic's official action, so the trust assumption is reasonable but not zero — a supply-chain compromise would silently affect this workflow.
- The size guard (100 files / 5,000 lines) is a hard cutoff. Large legitimate PRs (e.g., a generated SDK update) will be skipped without review.
- The "any comment containing `@claude`" pattern is permissive — quoted text in a reply triggers a re-review. Accepted as low-frequency, low-cost noise rather than complicating with regex.
- The 12,000-token output cap could truncate a review of a particularly large PR. The size guard mitigates this in practice.

## Revisit when

- Monthly Anthropic spend exceeds $3 for two consecutive months — adjust cap, skip rules, or downgrade to manual-only.
- A review demonstrates Sonnet 4.6 missing things Opus 4.7 catches — try Opus on the next 3 PRs and measure cost vs. value.
- A collaborator joins the project — decide whether to extend the `@claude` allow-list and accept shared budget exposure.
- A genuine large PR is consistently skipped by the size guard — raise the threshold or add a manual override path.
- `anthropics/claude-code-action` releases a `v2` (breaking) — evaluate the upgrade and decide whether to follow or pin further.
- This repo is made public — re-verify the `OWNER`-only check and consider tightening further (e.g., spend rate limiting).
- The Claude review feedback is consistently ignored by the author — review whether the integration is providing value or just generating noise.
