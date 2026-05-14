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
- `/retrospect` — on a feature branch: diff = `merge-base(main, HEAD)..HEAD`. On main: diff = `HEAD~1..HEAD` (the most recent merge).
- `/retrospect <base>` — diff against an explicit base.

---

## Live context

Branch: !`git branch --show-current`
Working tree: !`git status --short`
Recent commits: !`git log --oneline -5`

---

## Phase 1 — Verify a feature is ready to retrospect

Before any reflection:

1. **Determine the diff range.** If on a feature branch, use `git merge-base main HEAD` as the base. If on main, use the parent of HEAD (assumes the most recent commit is the squash-merge of the feature).
2. **Confirm there is something to retrospect.** If the diff is empty or only contains the plan doc, stop and tell the user there is nothing yet to reflect on — the retrospect is for completed features.
3. **Locate the plan.** Find the plan doc at `docs/03-services/<service>/plans/<slug>.md` (look for one added in the diff). If no plan was committed, note this — retrospecting without the plan limits what can be compared, but is not a hard blocker.

State the range explicitly to the user:

> Retrospecting on changes from `<base-sha>` to `<head-sha>` (<N> commits, <M> files). Plan doc: `<path or 'none committed'>`.

---

## Phase 2 — Read the diff

Run:

- `git log <base>..HEAD --oneline` — commit list
- `git diff --stat <base>..HEAD` — file change summary
- `git diff <base>..HEAD` — full diff (if not too large; cap at ~2000 lines for context)

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

6. **Stale docs.** "Which existing docs, READMEs, or CLAUDE.md sections are now out of date because of this change?"

Capture each answer verbatim — it becomes input for Phase 4.

---

## Phase 4 — Synthesize candidates

Classify the user's answers into three output streams. Show each candidate explicitly. Do not write anything yet.

### Memory candidates

For each insight that should persist across sessions:

> **Memory candidate** — type: `feedback` | `project` | `reference`
> **Slug:** `<kebab-case>`
> **Description:** <one line>
> **Body:**
> <proposed content, structured per the auto-memory rules in CLAUDE.md>
>
> _Save this memory?_ [Yes / Revise / Discard]

Memory types follow the rules in the system prompt's auto-memory section:
- `feedback` — for "this is how the user wants me to work" rules (with **Why:** and **How to apply:** lines).
- `project` — for who/what/why/when about ongoing work (with **Why:** and **How to apply:** lines).
- `reference` — for "where information lives" pointers.

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
>
> _Draft this ADR for me to review and commit? Or note as 'no ADR needed'?_

Use the next sequential ADR number (read `docs/06-decisions/` to find the highest existing number, then +1).

### Doc-update candidates

For each existing document that is now stale:

> **Doc update candidate** — file: `<path>`
> **Section:** <heading>
> **Current text:** <quote>
> **Proposed change:** <new text or specific edit>
>
> _Apply this edit?_ [Yes / Revise / Discard]

Common targets:
- `CLAUDE.md` Layer 2 (`Completed:` list, `Recent ADRs:` line, `Next milestone:`)
- `CLAUDE.md` Layer 3 (new pointers)
- `services/<service>/README.md`
- `infrastructure/environments/dev/README.md`
- Architecture docs in `docs/02-architecture/`

---

## Phase 5 — Approval and write

After all candidates are listed, walk them one at a time. For each:

1. Display the candidate.
2. Ask: `Save / Revise / Discard?`
3. If **Save**: write the artifact.
   - Memory: write the file under `/Users/davidfejes/.claude/projects/-Users-davidfejes-Projects-CollabSpace/memory/` and add the pointer line to `MEMORY.md`.
   - ADR: write to `docs/06-decisions/adr-NNN-<slug>.md`. Do not auto-update `CLAUDE.md`'s `Recent ADRs:` line — propose that as a separate doc-update candidate so the user explicitly approves the count change.
   - Doc update: apply the proposed edit to the named file.
4. If **Revise**: ask what to change, redraft, then re-ask.
5. If **Discard**: move on, no write.

Process candidates in this order: memory first, ADRs second, doc updates last. This is because doc updates often reference newly-written ADRs (`Recent ADRs: adr-NNN`) and need the ADR file to exist first.

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
> - Commit the new memory / ADRs / doc edits if they should be part of this PR's history, or as a follow-up `[fast]` commit.

Stop here. Do not propose further actions.

---

## Constraints

- **Propose, do not write.** Nothing — memory, ADR, doc edit — is written without explicit per-item approval from the user.
- **No silent inference.** All candidates trace back to a user answer in Phase 3. Do not invent insights from reading the diff alone.
- **Plan-vs-reality is the highest-value question.** Spend more time on it than on the others. Drift between plan and implementation is the most common source of learning that this skill exists to surface.
- **Be specific.** "We learned about authentication" is not a memory. "Spring Security's `BCryptPasswordEncoder` defaults to cost 10; we bumped to 12 in `SecurityConfig` to match the [authentication.md] design" is.
- **Don't retrospect on a Fast-tier change.** If the diff is purely a typo, dep bump, or doc edit, stop and say there is no retrospect needed.
- **Do not run on a feature mid-implementation.** If the working tree is dirty and the feature is not yet merged or about to merge, ask the user to confirm — retrospects on incomplete work are noise.
