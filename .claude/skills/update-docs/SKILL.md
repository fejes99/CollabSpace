---
name: update-docs
description: Documentation sync after code changes. Identifies every stale or missing doc based on what changed and makes surgical edits. Run after code changes and before /pre-commit.
disable-model-invocation: true
allowed-tools:
  - Read
  - Edit
  - Write
  - Grep
  - Bash(git diff *)
  - Bash(git status *)
  - Bash(git checkout *)
---

## Change snapshot

Changed files: !`git diff HEAD --stat`
Untracked files: !`git status --short`

Full diff:
!`git diff HEAD`

---

From the snapshot, classify every changed and untracked file:
- Type: `terraform-module` | `terraform-env` | `service` (note language) | `workflow` | `docs` | `config`
- Change: `new` | `modified` | `deleted`

This classification drives every step below.

---

## Phase 1 — Parallel reads

Read all of the following simultaneously before touching any file.

**Always read:**
- `CLAUDE.md`
- `README.md`
- `infrastructure/README.md`
- `infrastructure/environments/dev/README.md`
- `.github/workflows/README.md`
- `docs/07-development/commit-checklist.md`

**Per changed file — read its module/service README if it exists:**
- `infrastructure/modules/<name>/*.tf` changed → read `infrastructure/modules/<name>/README.md`
- `services/<name>/` changed → read `services/<name>/README.md`

Note any module or service with no README — you will create one in Phase 3.

**Read source files for every README you will audit:**
- Changed Terraform module → `main.tf`, `variables.tf`, `outputs.tf`
- Changed service → entry point and route/handler definitions
- Changed workflow → the `.yml` file itself

Auditing a README without reading its source produces inaccurate output.

---

## Phase 2 — Gap analysis

Before editing anything, enumerate what is stale, missing, or incorrect per file.

**CLAUDE.md Layer 2**
- `Current goal` — still accurate?
- `Next milestone` — has this change completed part of it? Rewrite to show only what remains.
- `Completed` — append what this change finished
- `Blocked on` — new blockers or resolved ones?
- `Recent ADRs` — new ADR number listed?
- `Layer 3 Pointers` — new file needs a pointer entry?

**Root README.md**
- Status block: stage description and "currently live" sentence match reality?
- Tech Stack table: any technology, version, or service changed?
- Running the Project: any step now wrong?

**infrastructure/README.md**
- Module table: every dir in `infrastructure/modules/` has a row linking to an existing README
- Dev environment paragraph: matches actual current state of `environments/dev/`

**infrastructure/environments/dev/README.md** — compare directly against `main.tf`:
- "What it creates": every `module` block has a row; Notes column in present tense — no "placeholder" or "when X is built" for things now built
- "Not created here": remove things that now exist; add newly deferred items
- Cost table: reflects current resources
- "What comes next": actual next step given what this change accomplished

**Module READMEs** (for every changed `infrastructure/modules/*/`)
- "What it creates": every `resource` block present; removed resources removed
- Inputs: every variable has a row; Type, Default, Description match `variables.tf`
- Outputs: every output has a row
- Usage example: syntactically valid and includes all required variables
- New non-obvious decision (lifecycle block, specific flag, workaround) → "Why X?" paragraph

**.github/workflows/README.md**
- Every `.yml` in `.github/workflows/` is in the Active table
- Planned → now implemented: move to Active, status `Live`
- New planned workflows identified by this change: add to Planned table

**Service READMEs** (for every changed service file)
- README reflects what the service now does
- New endpoint added → documented
- First creation → no README yet; create one

---

## Phase 3 — Edit

Apply all updates from Phase 2 in this order:
1. `CLAUDE.md`
2. New READMEs for modules or services with none
3. Module and service READMEs for changed code
4. `infrastructure/environments/dev/README.md`
5. `infrastructure/README.md`
6. Root `README.md`
7. `.github/workflows/README.md`

**Rules:**
- `Edit` for existing files — change only what is wrong; do not rewrite correct sections
- `Write` for new files only
- No meta-commentary in docs ("Updated by Claude", "as of this change")
- No speculative content unless the doc already has a "Planned" section
- Present tense throughout: remove past-tense for done things, remove future-tense for things that now exist

**New Terraform module README structure:**
```markdown
# Module: <name>
One-line description.

## What it creates
| Resource | Purpose |
|----------|---------|

## Why <decision> (if applicable)
<explanation of non-obvious behaviour>

## Usage
\`\`\`hcl
<minimal valid example including all required variables>
\`\`\`

## Inputs
| Name | Type | Default | Description |
|------|------|---------|-------------|

## Outputs
| Name | Description |
|------|-------------|
```

**New service README structure:**
```markdown
# <Service Name>
One-paragraph description of what this service does and its role in the system.

## Running locally
<commands>

## Environment variables
| Name | Required | Description |
|------|----------|-------------|

## API
<endpoint list or link to OpenAPI spec>

## Testing
<how to run tests>
```

---

## Phase 4 — Verify

Run `git diff --stat` and confirm:
- Modified files match the intended edits from Phase 2
- No source files (`.tf`, `.java`, `.ts`, `.py`, `.yml`) appear in the diff

If a source file appears in the diff: run `git checkout -- <file>` immediately — this skill modifies documentation only.

---

## Output

---
### Documentation updated
**[filepath]**
- [what was stale or missing]
- [what was changed]

### New files created
[filepath — one-line description, or "None"]

### Checked and already current
[list or "None"]

### Needs your input
[Items that cannot be updated without your context — be specific. Or "None."]

### Next step
Run `/pre-commit` — documentation is current.
[Or: Resolve items under "Needs your input" first.]
---
