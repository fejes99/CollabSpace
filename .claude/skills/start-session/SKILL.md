---
name: start-session
description: Session opener — loads live project state from CLAUDE.md and git, reports current stage/goal/scope, flags any drift from the last session, and asks what to work on. Run at the start of every session before any work begins.
disable-model-invocation: true
allowed-tools:
  - Read
  - Bash(git log *)
  - Bash(git status *)
  - Bash(git branch *)
  - Bash(git stash list)
  - Bash(git diff *)
  - Bash(find docs/06-decisions -name "*.md" -type f)
---

## Live context

Branch: !`git branch --show-current`
Working tree: !`git status --short`
Stash: !`git stash list`
Recent commits: !`git log --oneline -10`
ADR files: !`find docs/06-decisions -name "*.md" -type f | sort`

---

## Phase 1 — Load

Read in parallel:
- `CLAUDE.md` — full file, all four layers
- `docs/07-development/commit-checklist.md`

Then read 1–3 additional files from **Layer 3 Pointers** in CLAUDE.md — only the ones directly relevant to the **Current service** and **Next milestone** in Layer 2. Do not read everything; relevance beats completeness.

Heuristics:
- Stage 1 / auth-workspace: always read `infrastructure/environments/dev/README.md`
- If "Next milestone" mentions a specific module: read that module's README
- If "Next milestone" mentions a workflow: read `.github/workflows/README.md`
- If "Next milestone" mentions a service: read `services/<name>/README.md` if it exists

Cap at 3 files beyond `CLAUDE.md` and the checklist.

---

## Phase 2 — Alignment checks

Silently verify each of the following. Record results for Phase 3 output.

**Git ↔ Layer 2 consistency**
- Does the most recent commit message correspond to something in the "Completed" list in Layer 2?
- If recent commits exist that are NOT reflected in "Completed" → flag: "Commits ahead of CLAUDE.md — Layer 2 may need updating."
- If "Completed" claims something that cannot be seen in the last 10 commits → flag: "CLAUDE.md claims completion not visible in recent git history."

**In-progress work from a prior session**
- Any modified or staged files in `git status`? If yes, list them — they may be leftover work.
- Anything in the stash? If yes, note count and top stash message.

**ADR count**
- Count the ADR files found by the `find` command above.
- Compare to the "Recent ADRs: adr-001 to adr-NNN" line in Layer 2.
- If the file count and the stated range don't match → flag: "ADR count mismatch."

**Blocked on**
- If Layer 2 "Blocked on" is not "nothing" or empty, surface the blocker prominently.

If all checks pass, the alignment summary is: "None — git and CLAUDE.md are consistent."

---

## Phase 3 — Session brief

Produce exactly the structure below. One value per field. No prose, no preamble, no commentary outside this block.

---
**CollabSpace — Session Brief**

**Stage:** [Layer 2: current stage]
**Service:** [Layer 2: current service]
**Goal:** [Layer 2: current goal — one sentence verbatim]
**Out of scope:** [Layer 2: out of scope — verbatim]
**Next milestone:** [Layer 2: next milestone — verbatim]
**Blockers:** [Layer 2: blocked on — or "None"]

**Git state**
- Branch: [branch name]
- Last commit: [short hash] [message]
- Working tree: [Clean / X modified / X staged+modified — list files if ≤ 5]
- Stash: [Empty / N entries — show top message if any]

**Alignment**
[One line per flag from Phase 2, or "None — git and CLAUDE.md are consistent."]

---

Then ask:

> What do you want to work on this session?

---

## Constraints (active for the entire session)

Do not generate any files until the user confirms what they want to work on.
Do not propose any plans or implementations until the user responds.
Do not start any task.

Once the user answers, the following rules from CLAUDE.md Layer 1 govern the session:
- BEFORE generating any non-trivial file, propose what you're about to create and why. Wait for approval.
- For changes touching > 3 files, propose a plan and wait for approval.
- Never run `terraform apply` without showing the plan first and getting explicit approval.
- Cite the relevant ADR when making non-trivial choices. If no ADR exists, say so and offer to write one.
- When you DO generate a file, walk through key decisions AFTER generation, before the user commits.
