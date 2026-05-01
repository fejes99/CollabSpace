---
name: pre-commit
description: Pre-commit review — audits staged changes for secrets, anti-patterns, documentation gaps, and missing ADRs. Run before every git commit, after /update-docs.
disable-model-invocation: true
allowed-tools:
  - Read
  - Grep
  - Bash(git diff *)
  - Bash(git log *)
  - Bash(git status *)
  - Bash(git branch *)
---

## Staged snapshot

Branch: !`git branch --show-current`
Staged summary: !`git diff --cached --stat`
Unstaged summary: !`git diff --stat`
Recent commits: !`git log --oneline -5`

Full staged diff:
!`git diff --cached`

---

## Phase 1 — Classify and load

From the snapshot above, classify each staged file:
- Type: `terraform-module` | `terraform-env` | `java` | `typescript` | `python` | `workflow` | `docs` | `config`
- Change: `new` | `modified` | `deleted`

Read in parallel:
- `docs/07-development/commit-checklist.md`
- `CLAUDE.md` — Layer 1 (code style), Layer 2 (current state), Layer 4 (anti-patterns)

Flag any unstaged changes — they may belong in this commit or need stashing.

---

## Phase 2 — Always checks

Mark each ✅ pass / ⚠️ advisory / ❌ blocking.

**Secrets and hygiene**
- No `.env` files, secrets, tokens, passwords, or AWS credentials in the diff (including comments)
- No debug noise: `console.log`, `System.out.println`, `print()`, `debugger`, commented-out blocks
- No unresolved `TODO`/`FIXME` this commit was supposed to close

**Scope**
- Commit does one logical thing — flag mixed concerns and suggest splitting

**Branch**
- Direct commit to `main` is only allowed for infrastructure work during Stage 0 (see CLAUDE.md)

---

## Phase 3 — Documentation audit

For every non-documentation file changed, trace impact to docs. For each gap, write the exact corrected text — not "update the README" but the actual replacement content.

**CLAUDE.md Layer 2** (always check)
- `Current goal` — still accurate?
- `Next milestone` — completed by this commit? Rewrite to show only what remains.
- `Completed` — anything to append?
- `Blocked on` — new blockers or resolved ones?
- `Recent ADRs` — new ADR number missing?
- `Layer 3 Pointers` — new module/service/doc needs a pointer entry?

**Root README.md** — Status block reflects current state?

**infrastructure/README.md** — Module table has a row + README link for every dir in `infrastructure/modules/`?

**Module READMEs** (if any `infrastructure/modules/**/*.tf` changed)
- "What it creates" matches `main.tf` resources
- Inputs table matches `variables.tf` (type, default, description)
- Outputs table matches `outputs.tf`
- Usage example valid and includes all required variables
- New module with no README → ❌ blocking

**environments/dev/README.md** (if `infrastructure/environments/dev/` changed)
- "What it creates" table matches every `module` block in `main.tf`
- No stale placeholder or future-tense language for things now built
- "What comes next" reflects the actual next step

**.github/workflows/README.md** (if a workflow changed)
- New workflow → row in Active table
- Planned workflow now implemented → moved to Active, status `Live`

**Service READMEs** (if any service code changed) — README reflects the change?

---

## Phase 4 — Conditional code checks

Run only the sections matching file types from Phase 1.

**Terraform**
- No obvious `terraform fmt` violations visible in the diff
- All new resources tagged: `Environment`, `Service` (where applicable), `ManagedBy = "terraform"`
- `for_each` not `count` for multi-instance resources
- No hardcoded account IDs, region strings, or ARNs — variables or data sources only
- New variables have complete descriptions in `variables.tf`

**Java (Spring Boot)**
- No `@Autowired` on fields — constructor injection only
- DTOs use records
- `Optional<T>` over null returns
- `@Transactional` on service methods spanning multiple repository calls
- `jakarta.validation` at controller boundary only — not in service or repository
- No direct DB access from controllers

**TypeScript**
- No `any` without an inline comment justifying it
- No default exports
- No `console.log` — pino only
- External input validated with `zod`
- No `.then()/.catch()` chains — async/await only

**Python**
- Type hints on all public function signatures
- Pydantic models for request/response
- `async def` for I/O-bound functions
- No mutable default arguments
- `structlog` only — not `print()` or `logging`

---

## Phase 5 — ADR and code scan

**ADR check** — if this commit makes a non-trivial architectural decision (tech selection, trade-off, cost-impacting pattern):
- ADR committed alongside this change?
- ADR has all sections: Status, Date, Context, Decision, Alternatives Considered, Consequences (+ and −), Revisit when
- Implementing code cites the ADR number in a comment

**Code scan** — read each changed non-documentation file. Flag only what is worth fixing *within this commit's scope*; do not propose refactors of untouched code:
- CLAUDE.md Layer 4 anti-patterns
- Missing error handling at system boundaries (user input, external APIs, file I/O)
- Logic that will confuse a reader in three months

---

## Output

Use this exact structure. No commentary outside it.

---
### Staged files
`path` — type — new/modified/deleted

### Unstaged changes
[list or "None"]

### Checklist result

❌ **Blocking** (must resolve before committing):
[numbered list or "None"]

⚠️ **Advisory** (worth addressing, does not block):
[numbered list or "None"]

✅ **Passed:** [summary of what was checked and is clean]

### Documentation updates needed
**File → Section** — exact replacement text, ready to apply
[or "None — documentation is current"]

### Code improvements
`path:line` — specific suggestion
[or "None"]

### Suggested commit message
```
<imperative subject line ≤72 chars>

<body: why — omit if subject is self-explanatory>
```
---

**If ❌ blocking issues exist:** stop. Do not provide a commit message. Wait for resolution.
