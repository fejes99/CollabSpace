# Feature Workflow

This document defines how a feature moves from idea to merged PR running on AWS. It is the procedure for every behavior change in Stage 2 and beyond.

It exists because Stage 2 will produce dozens of features across five services. Without a fixed process, quality, test coverage, and documentation drift in that order, fastest when work gets hard. The procedure is rigorous on purpose — the goal is not just to ship, it is to internalize habits that turn a junior engineer into someone who has seen and survived a production codebase. The process is the curriculum.

**Cross-references**

- Per-commit hygiene: [commit-checklist.md](commit-checklist.md). This document governs the _feature_; the checklist governs each _commit_ inside the feature.
- Test details: [testing-strategy.md](testing-strategy.md).
- MVP scope: [../roadmap.md](../roadmap.md). The MVP per-service baseline (Category A) defines what "done" means for a service.
- Skills used: `/start-session`, `/plan-feature`, `/pre-commit`, `/update-docs`, `/retrospect`.

---

## Tiers

Not all changes carry the same risk. A typo fix should not require a planning document. A new authenticated endpoint must. There are three tiers; default to **Full** unless you can affirmatively justify a smaller one.

| Tier      | When it applies                                                                                      | Plan doc                                                        | Test requirements                     | Retrospect |
| --------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- | ------------------------------------- | ---------- |
| **Full**  | Schema change, new dependency, >1 endpoint, ADR-worthy decision, anything auth- or security-touching | Full template (9 sections)                                      | Integration test + edge case tests    | Yes        |
| **Small** | One endpoint, no schema change, no new dependency, no ADR-worthy choice                              | Six-section template (slice statement + user-visible behavior + API contract + validation + observability + out of scope) | Happy-path integration + 2 edge cases | Optional   |
| **Fast**  | Typo, dep bump, doc edit, single-line CI tweak, log-level change, README polish                      | None                                                            | Existing CI lint and tests must pass  | None       |

The PR title carries the tier as a prefix: `[full]`, `[small]`, `[fast]`. CI does not enforce it — it is a self-discipline signal and a review hint.

**Defaulting upward.** When in doubt, pick the larger tier. The cost of over-engineering a small feature is a few wasted minutes. The cost of under-engineering a real feature is a production bug or an undocumented decision that confuses you in three weeks.

**Promoting mid-feature.** If a "Small" feature reveals a schema change requirement halfway through, stop, promote it to Full, and write the missing plan sections retroactively. Do not silently expand scope without updating the artifact.

---

## The Full loop (8 phases)

The procedure for any non-trivial feature.

### Phase 0 — Slice

Decide what is in this feature and what is not. Write it down before opening any file.

A vertical slice is one user-visible behavior, end-to-end:

> "A user can register with email and password, and receive a JWT in response."

Not:

> "Authentication."

If your slice contains the word "and" twice, it is two slices. Split it.

Write three IN bullets and three OUT bullets. The OUT list is the anti-creep guard — when you find yourself tempted to build them anyway mid-feature, the OUT list is what reminds you why not.

Output of this phase: a slice statement that fits on a sticky note.

### Phase 1 — Plan

Create the plan document at `docs/03-services/<service>/plans/<feature-slug>.md`.

Required sections for a Full plan:

1. **Slice statement** — from Phase 0.
2. **User-visible behavior** — what an external observer can verify.
3. **API contract** — method, path, request schema, response schema, status codes for happy path and each error.
4. **Data model changes** — migration sketch, new tables, columns, indexes.
5. **Validation rules** — table of input field → constraint → error code.
6. **Edge cases** — table of scenario → expected response.
7. **Authorization** — who can call this; what claim is required; behavior for unauthenticated and wrong-role requests.
8. **Observability** — log lines emitted, correlation ID propagation, audit events.
9. **Out of scope** — the OUT list from Phase 0.

When the draft is ready, run `/plan-feature <slug>` (or paste the doc into chat with "review this adversarially"). Claude critiques the plan: missing edge cases, ambiguity in the API contract, validation gaps, authorization holes. Revise until the critique is empty.

**Do not skip this phase.** A bad plan produces three days of throwaway code. A good plan produces three hours of focused code. The math is the same every time.

### Phase 2 — Branch and schema

```bash
git checkout main
git pull origin main
git checkout -b feat/<service>/<feature-slug>
```

Write the database migration first. Apply it locally. Roll it back. Apply it again. This proves you have a working recovery path before any business code is written.

Stub the controller, service, and repository with empty methods — signatures, types, and `TODO` comments only. Commit these stubs. Push as a **draft PR**. Watch CI compile.

Why draft early? CI feedback is most valuable when the change is smallest. A type error in 50 lines is a two-minute fix; the same error after 500 lines is a half-hour archaeology dig.

### Phase 3 — Happy path

Write **one integration test** that exercises the happy path against a real database (Testcontainers — see [testing-strategy.md](testing-strategy.md)). Run it — it should fail. Your stubs compile but have no logic yet; a failing test is the correct state. Commit the test file with a `[red]` prefix — e.g., `[red] Add failing integration test for happy-path registration`. No implementation code in this commit.

Wire the happy path end-to-end: one request in, hits the controller, goes through the service, talks to the repository, returns a response. Run the test again — get it green. `./mvnw test` must pass before you commit the implementation.

Manually smoke-test with curl. Save the curl command — it goes into the PR description as the manual test plan.

Stop here and confirm the slice does what the plan said it would. If not, the plan was wrong; revise it before continuing. Never let code drift from the plan silently.

### Phase 4 — Edge cases

For each row in the edge-cases table of your plan, write one test. Categories:

- **Validation** — empty inputs, too-long inputs, malformed inputs, boundary values.
- **Authorization** — unauthenticated, wrong workspace, insufficient role.
- **Conflict and not-found** — resource exists already (409), resource does not exist (404).
- **Observability** — correlation ID is propagated; audit log line is emitted.

Each test is deterministic and parallel-safe. If a test passes once and fails the next time, fix the flake before continuing — a flaky test is worse than no test because it teaches you to ignore failures.

### Phase 5 — Polish

Run `/pre-commit` to walk the existing checklist. Then:

- Read the entire PR diff as if it were not yours. Look for: unused imports, dead branches, commented-out code, leaked `TODO`s, magic numbers without comments.
- Update the OpenAPI spec; verify it matches the actual response shape.
- Update the service README if behavior changed.
- Write an ADR if a non-obvious choice was made (see ADR conventions in [../../CLAUDE.md](../../CLAUDE.md)).

### Phase 6 — Deploy and verify

Mark the PR ready for review. This triggers the Claude PR review workflow (see [ADR-024](../06-decisions/adr-024-claude-pr-review.md)) — wait for the review comment to appear, then address each point or explicitly note "accepted as-is, reason: …" in a reply. Re-trigger with a comment containing `@claude` after revisions if needed. CI must be green. Squash-merge to main.

```bash
make dev-up   # if the dev environment is destroyed
```

Watch the deploy workflow. Once the service is stable, hit the endpoint against AWS with curl. Save the response in the PR description — the actual AWS response, with timestamp.

A feature is not done until it has been verified against deployed infrastructure. Local-only verification has shipped a thousand bugs.

### Phase 7 — Retrospect

Run `/retrospect` (or manually prompt Claude with "review this merged feature: what would you do differently"). The skill reads the diff between merge-base and HEAD and asks:

- What took longer than expected, and why?
- What surprised you?
- Did this introduce a new pattern? Should it become a memory or an ADR?
- What documentation is now stale because of this change?

It proposes memory entries, ADR drafts, and doc updates. Each one requires explicit approval before it is written (memory entries are an exception — auto-memory is curated, not committed, so Claude can save them after showing them to you in chat).

**Exit criterion.** Happy path works on AWS. Tests are green. Plan, OpenAPI, README, and (if relevant) ADR are current. Retrospect is run. Branch is deleted.

---

## The Small loop

For one-endpoint changes with no schema change and no new dependency.

1. **Slice and plan** — run `/plan-feature <service> <slug>`. For Small, the skill walks the slice step plus six plan sections (slice statement + user-visible behavior + API contract + validation + observability + out of scope) and runs adversarial review. Adversarial review is not optional.
2. **Branch and wire** — branch off main, implement, test happy path + 2 edge cases.
3. **Polish** — pre-commit checklist; update OpenAPI; update README if needed.
4. **Deploy and verify** — squash-merge, watch CI, curl AWS.

Phase 7 is optional. One day max. If it grows beyond one day, promote it to Full and write the missing artifacts.

---

## The Fast loop

For trivial changes: typo, dep bump, doc edit, single-line CI tweak, log-level change.

1. Branch — `chore/<slug>` or `docs/<slug>` or `fix/<slug>` depending on intent.
2. Change, commit, push.
3. PR title prefix: `[fast]`.
4. CI must pass.
5. Squash-merge.

No plan, no tests, no retrospect. If you find yourself wanting tests, the change wasn't Fast — promote.

---

## Branching strategy

Trunk-based, short-lived feature branches, squash-merge.

### Naming

```
feat/<service>/<slug>     new behavior in a service
fix/<service>/<slug>      bug fix in a service
infra/<slug>              terraform / CI / pipelines / scripts
docs/<slug>               documentation only
chore/<slug>              dependency bumps, version pins, cleanup
```

`<service>` is the short service name without prefix: `auth`, `document`, `realtime`, `assistant`, `notification`. The `<slug>` is 2–4 kebab-case words: `user-registration`, `email-validation`, `jwt-refresh-rotation`.

### Lifetime

Two working days maximum. A branch that lives longer accumulates divergence, becomes hard to rebase, and almost certainly contains a slice that was too big. Split it.

### Synchronizing with main

Rebase, do not merge:

```bash
git fetch origin
git rebase origin/main
```

The result is a linear history. No merge commits from main into the feature branch.

If a rebase conflict is non-trivial, that itself is a signal that the branch is growing stale — finish what you have, merge, and start the rest as a new branch.

### Merging

Squash-merge through GitHub. The branch's WIP commits become one clean commit on main with the PR title as the message and the PR description as the body. The remote branch is **auto-deleted by GitHub** (repo setting *Automatically delete head branches* is enabled). Sync main and clean up the local branch:

```bash
git checkout main
git pull origin main
git branch -d feat/<service>/<slug>
```

`git branch -d` will print a warning that the branch is not merged to HEAD — that's expected after a squash-merge, since the squashed commit on main has a different SHA than the feature branch's commits. The branch is still safely deleted; git compares against the remote tracking ref, which knows the PR was merged.

### Direct commits to main

Disallowed in Stage 2 and beyond. The Stage 1 → Stage 2 transition commit on `main` is the last permitted direct-to-main commit; from that branch point forward, every change goes through a PR. The only exception is a Stage 0 / Stage 1 hotfix to recover the build, which has not happened yet and should require a written justification when it does.

---

## Pull request conventions

### Title

Imperative mood, ≤72 chars, tier prefix:

```
[full]  Add user registration endpoint to auth-workspace
[small] Return 409 on duplicate email in /v1/auth/register
[fast]  Bump Fastify from 5.0.0 to 5.0.1 in document-service
```

### Body

Three required sections.

**What** — one paragraph describing the change.

**Why** — one paragraph describing the motivation. Link the plan doc.

**Test plan** — checklist:

- [ ] Integration tests pass
- [ ] Manual smoke: curl command pasted below
- [ ] AWS smoke: live response pasted below **post-merge** — `dev` is a single shared
      environment and only deploys on push to `main` (ADR-022), so this cannot be
      verified pre-merge
- [ ] OpenAPI spec updated
- [ ] Service README updated
- [ ] CLAUDE.md Layer 2 updated

The AWS response goes in a fenced block at the bottom, pasted post-merge. The exact bytes, not a summary.

### Draft vs ready

Open as draft as soon as you have stubs that compile (end of Phase 2). Mark ready only when the test plan is complete and CI is green.

### Self-review

Even solo, you review your own PR. Reading the diff in GitHub's PR view (not in the IDE) catches things the IDE hid: large files inadvertently committed, unrelated changes that crept in, formatting drift. Claude is the second reviewer — paste the diff with "what's wrong here, what would fail in production."

---

## Definition of Done (feature-level)

A feature is done when all of these are true.

**Before merge — the gate before squash-merging:**

- [ ] Plan document committed at `docs/03-services/<service>/plans/<slug>.md`
- [ ] Plan was reviewed adversarially (`/plan-feature` or manual prompt)
- [ ] Happy-path integration test green against a real DB
- [ ] Edge-case tests cover validation, authorization, conflict/not-found, observability
- [ ] Manual smoke run against the local stack
- [ ] OpenAPI spec updated and matches actual response shape
- [ ] Service README updated to reflect new behavior
- [ ] CLAUDE.md Layer 2 `Completed:` list updated
- [ ] ADR written if a non-obvious decision was made; cross-linked from the relevant plan doc/README
- [ ] CI green on the PR
- [ ] Squash-merged to main

Treat this list as a hard gate. If any item is incomplete, the PR does not merge — even if the code works.

**After merge — required to close the PR/issue, not to merge it:**

`dev` is a single shared AWS environment (ADR-022) with no pre-merge sandbox, and
`service-auth.yml`-style workflows only build and deploy on push to `main`. AWS
verification is therefore structurally a post-merge step:

- [ ] Endpoint hit on the live AWS dev environment; response saved in the PR
- [ ] `/retrospect` run; learnings captured (memory entries approved, doc updates proposed and accepted or declined)
- [ ] Branch deleted locally and remotely

---

## Skills referenced

| Skill            | Phase         | Purpose                                                     |
| ---------------- | ------------- | ----------------------------------------------------------- |
| `/start-session` | Session start | Orient to project state before any work                     |
| `/plan-feature`  | Phase 1 / Small step 1 | Interactive plan drafting + adversarial review              |
| `/pre-commit`    | Phase 5       | Walk the per-commit checklist                               |
| `/update-docs`   | Phase 5 / 6   | Sync READMEs, CLAUDE.md, ADR list with changes              |
| `/retrospect`    | Phase 7       | Reflect on what was learned; propose memory and doc updates |

`/start-session` always precedes `/plan-feature`.

---

## Why this much process for a learning project

Two reasons.

**One:** the process _is_ the curriculum. Every phase teaches a habit that production engineers internalized through pain: planning before coding, testing before shipping, verifying on real infrastructure, reflecting after merging. Skipping a phase saves time today and costs a lesson forever.

**Two:** the process is what makes Claude useful. A vague instruction ("build user registration") produces vague output. A planned, sliced, scoped feature with explicit edge cases produces code you can read, understand, and own. The output quality you get from a coding assistant is directly proportional to the precision of the input you give it.
