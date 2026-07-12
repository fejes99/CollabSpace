---
name: retrospect
description: Post-merge feature retrospective. Reads the diff between the feature branch and main, walks the user through reflective questions, and proposes candidate memory entries, ADR drafts, and doc updates. Proposes only — nothing is written without explicit per-item approval. Run at workflow Phase 7, after the feature is merged (or just before).
disable-model-invocation: true
allowed-tools:
  - Read
  - Write
  - Edit
  - Bash(git diff *)
  - Bash(git log *)
  - Bash(git merge-base *)
  - Bash(git branch *)
  - Bash(git status *)
  - Bash(ls *)
  - AskUserQuestion
---

## Arguments

Optional `<base>` — the base branch or commit to diff against. Defaults to `main` (or `origin/main` if available).

Usage:
- `/retrospect` — on a feature branch: diff = `merge-base(main, HEAD)..HEAD`. On main: diff = from the most recent merge commit's parent to HEAD (see Phase 1 for how the merge commit is identified).
- `/retrospect <base>` — diff against an explicit base.

---

## Live context

Branch: !`git branch --show-current`
Working tree: !`git status --short`
Recent commits: !`git log --oneline -5`

---

## Phase 1 — Verify a feature is ready to retrospect

Before any reflection:

1. **Determine the diff range.** If on a feature branch, use `git merge-base main HEAD` as the base. If on main, run `git log --oneline -10` and identify the most recent merge commit (subject typically starts with "Merge" or matches a PR squash pattern). Use that commit's parent as the base. If the most recent commit is ambiguous, show the log and ask the user to confirm which commit to diff against.
2. **Confirm there is something to retrospect.** If the diff is empty or only contains the plan doc, stop and tell the user there is nothing yet to reflect on — the retrospect is for completed features.
3. **Locate the plan.** Find the plan doc at `docs/03-services/<service>/plans/<slug>.md` (look for one added in the diff). If no plan was committed, note this — retrospecting without the plan limits what can be compared, but is not a hard blocker.

State the range explicitly to the user:

> Retrospecting on changes from `<base-sha>` to `<head-sha>` (<N> commits, <M> files). Plan doc: `<path or 'none committed'>`.

---

## Phase 2 — Read the diff

Run:

- `git log <base>..HEAD --oneline` — commit list
- `git diff --stat <base>..HEAD` — file change summary
- `git diff <base>..HEAD` — full diff. If the diff exceeds ~2000 lines, do not load it all — instead read only the most significant changed files (service code, migrations, tests) in priority order, and skip generated files, lockfiles, and docs.

Build a mental model of what changed: which files in which services, schema migrations, new tests, new dependencies, new docs.

Read the plan doc if one exists. Note discrepancies between plan and implementation — these are prime retrospect material.

---

## Phase 3 — Reflective questions

Ask the user in sequence, one question at a time. Do not bundle.

1. **Plan vs reality.** "Looking at the plan and the diff: did the implementation match the plan, or did it drift? If it drifted, what changed and why?"

2. **Time and effort.** "What took longer than you expected, and why?"

3. **Surprises.** "What surprised you while building this? An unexpected error, a tool quirk, a constraint you hadn't planned for?"

4. **New patterns.** "Did this introduce a new pattern — a way of solving a class of problem — that other features should follow?"

5. **Hidden constraints.** "Did you discover a constraint or invariant that wasn't documented anywhere? (Behavior of a library, an AWS quirk, a framework convention.)"

6. **Architecture drift.** "Did this change reveal anything that the architecture docs (`docs/02-architecture/`) or existing ADRs don't reflect? A design assumption that turned out wrong, a constraint that wasn't documented, a pattern that diverged from the documented approach?"

Capture each answer verbatim — it becomes input for Phase 4.

---

## Phase 4 — Synthesize candidates

Classify the user's answers into three output streams. Show each candidate explicitly. Do not write anything yet.

Candidates may be prompted by diff observations from Phase 2 — but the candidate body must incorporate the user's own words from Phase 3. Present diff-derived observations as questions during Phase 3 ("I noticed X in the diff — is there a lesson to capture here?") and use the user's response as the candidate source. Do not write a candidate from diff observation alone.

### Memory candidates

For each insight that should persist across sessions:

> **Memory candidate** — type: `feedback` | `project` | `reference`
> **Slug:** `<kebab-case>`
> **Description:** <one line>
> **Body:**
> <proposed content, structured per the auto-memory rules in the system prompt>

Memory types and body structure follow the system prompt's auto-memory section.

If a memory updates an existing entry rather than creating a new one, say so explicitly and show the proposed diff to the existing entry.

### ADR candidates

For each non-trivial decision that emerged during implementation:

> **ADR candidate** — proposed number: `adr-NNN-<kebab-case-title>`
> **Status:** Proposed
> **Context:** <one paragraph>
> **Decision:** <one paragraph>
> **Alternatives considered:** <bulleted list>
> **Consequences:**
> - + <positive>
> - − <negative>
> **Revisit when:** <trigger condition>

Use the next sequential ADR number (read `docs/06-decisions/` to find the highest existing number, then +1).

### Doc-update candidates

For each existing document that is now stale:

> **Doc update candidate** — file: `<path>`
> **Section:** <heading>
> **Current text:** <quote>
> **Proposed change:** <new text or specific edit>

Common targets:
- `CLAUDE.md` Layer 2 (`Next milestone:`, `Current goal:`)
- `CLAUDE.md` Layer 3 (new pointers)
- Architecture docs in `docs/02-architecture/` — for design assumptions or constraints that turned out wrong
- ADRs whose `Revisit when` condition has been met
- If a new ADR was written this session: the relevant plan doc and/or affected service's README, so the ADR is cross-linked from wherever the decision is actually discussed — there is no central `Recent ADRs` list in `CLAUDE.md`

Do not re-audit service READMEs, infrastructure tables, or workflow docs here — that is `/update-docs`' domain. Only propose doc updates for the *why-layer*: things that changed in understanding, not just in code.

---

## Phase 5 — Approval and write

After all candidates are listed, walk them one at a time. For each:

1. Display the candidate.
2. Ask: `Save / Revise / Discard?`
3. If **Save**: write the artifact.
   - Memory: write the file under `/Users/davidfejes/.claude/projects/-Users-davidfejes-Projects-CollabSpace/memory/` and add the pointer line to `MEMORY.md`.
   - ADR: write to `docs/06-decisions/adr-NNN-<slug>.md`. Do not auto-cross-link it from other docs — propose each cross-link as a separate doc-update candidate so the user explicitly approves where it gets referenced.
   - Doc update: apply the proposed edit to the named file.
4. If **Revise**: ask what to change, redraft, then re-ask.
5. If **Discard**: move on, no write.

Process candidates in this order: memory first, ADRs second, doc updates last. This is because doc updates often cross-link newly-written ADRs and need the ADR file to exist first.

---

## Phase 6 — Summary

Output a final summary block:

> ## Retrospect summary
>
> Range: `<base-sha>..<head-sha>` (<N> commits, <M> files)
> Plan doc: `<path or 'none'>`
>
> **Memories**
> - Saved: <count> — <list of slugs>
> - Discarded: <count>
>
> **ADRs**
> - Drafted: <count> — <list of numbers>
> - Discarded: <count>
>
> **Doc updates**
> - Applied: <count> — <list of paths>
> - Discarded: <count>
>
> **Next**
> - ADRs and doc edits: commit as part of this PR's history or as a follow-up `[fast]` commit.
> - Memory files live outside the repo (`~/.claude/projects/...`) — do not commit them.

Stop here. Do not propose further actions.

---

## Constraints

- **Propose, do not write.** Nothing — memory, ADR, doc edit — is written without explicit per-item approval from the user.
- **No silent inference.** All candidates trace back to a user answer in Phase 3. Do not invent insights from reading the diff alone.
- **Plan-vs-reality is the highest-value question.** Spend more time on it than on the others. Drift between plan and implementation is the most common source of learning that this skill exists to surface.
- **Be specific.** "We learned about authentication" is not a memory. "Spring Security's `BCryptPasswordEncoder` defaults to cost 10; we bumped to 12 in `SecurityConfig` to match the [authentication.md] design" is.
- **Don't retrospect on a Fast-tier change.** If the diff is purely a typo, dep bump, or doc edit, stop and say there is no retrospect needed.
- **Do not run on a feature mid-implementation.** If the working tree is dirty and the feature is not yet merged or about to merge, ask the user to confirm — retrospects on incomplete work are noise.
