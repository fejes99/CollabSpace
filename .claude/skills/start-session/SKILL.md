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

Read:
- `CLAUDE.md` — full file, all four layers

Then read 1–3 additional files from **Layer 3 Pointers** in CLAUDE.md — only the ones directly relevant to the **Current service** and **Next milestone** in Layer 2. Do not read everything; relevance beats completeness.

Heuristics:
- If Layer 2 `Current service` is set: read `services/<service>/README.md`
- If "Next milestone" mentions a specific Terraform module: read that module's README
- If "Next milestone" mentions a workflow: read `.github/workflows/README.md`
- If "Next milestone" mentions a stage transition: read `infrastructure/environments/dev/README.md`

Cap at 3 files beyond `CLAUDE.md`.

---

## Phase 2 — Alignment checks

Silently verify each of the following:

**Git ↔ Layer 2 consistency**
- Extract the key noun phrases from the `Next milestone` line in Layer 2 (e.g. "RDS PostgreSQL", "user registration", "JWT endpoints").
- Scan `git log --oneline -10` for any commit message that contains one or more of those exact phrases (case-insensitive).
- If a match is found → flag: "Commit `<hash> <message>` mentions `<matched phrase>` from Next milestone — Layer 2 may need updating."
- If no verbatim match, this check passes. Do not infer completion from paraphrased or loosely related commit messages.
- Do not read [docs/CHANGELOG.md](../../../docs/CHANGELOG.md) — completions moved there but it is not load-bearing for session start.

**In-progress work from a prior session**
- Any modified or staged files in `git status`? If yes, list them — they may be leftover work.
- Anything in the stash? If yes, note count and top stash message.

**ADR count**
- From the ADR file list above, extract the highest ADR number present (e.g. `adr-024-*.md` → `024`).
- Scan `git log --oneline -10` for any commit message referencing `ADR-NNN` where NNN is greater than the highest file number.
- If such a reference exists without a matching file → flag: "Commit references ADR-NNN but no file exists for it."
- If no mismatch, this check passes.

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

If **any alignment flag was raised** in Phase 2:

> One or more alignment issues were found (listed above). Fix them now before we start, or note and proceed?

- **Fix:** show the user the exact replacement text for the stale CLAUDE.md Layer 2 field(s) — `Next milestone`, `Current goal`, or `Blocked on` — so they can apply the edit before work begins. Do not edit CLAUDE.md automatically during session start.
- **Proceed:** continue as-is; the flag stands in the brief as a reminder.

Then ask:

> What do you want to work on this session?

---

## Constraints (active for the entire session)

Do not generate any files until the user confirms what they want to work on.
Do not propose any plans or implementations until the user responds.
Do not start any task.
