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

**Size check first.** If the staged summary shows > 50 changed files or > 2000 lines: tell the user this is a large commit, note that the full diff may exceed review capacity, and restrict Phase 4 code checks to non-generated files only — skip `package-lock.json`, `pnpm-lock.yaml`, `*.snap`, and generated `*.d.ts` files.

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
- Commit does one logical thing — if the diff touches files from more than two unrelated concerns (e.g. service code + CI workflow + unrelated config), flag as mixed and suggest splitting

**Branch**
- Direct commit to `main` is only allowed for infrastructure work during Stage 0 (see CLAUDE.md)

---

## Phase 3 — Documentation audit

Before proposing any correction, read each doc you will audit — you cannot write accurate replacement text for a file you have not read.

For each changed file type, apply the relevant sub-section from commit-checklist.md's **Documentation** section (already loaded in Phase 1). For each gap found, write the exact corrected text — not "update the README" but the actual replacement content.

**CLAUDE.md Layer 2** (always check — these fields are the most frequently stale):
- `Current goal` — still accurate?
- `Next milestone` — completed by this commit? Rewrite to show only what remains.
- `Blocked on` — new blockers or resolved ones?
- `Layer 3 Pointers` — new module/service/doc needs a pointer entry?
- New ADR this commit? Check it's cross-linked from the relevant plan doc / service README — there is no `Recent ADRs` line in `CLAUDE.md`.

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
- No `UnsupportedOperationException`/TODO stub inside a `@Bean`, `@Component`, `@Service`, or `@Configuration` class — flag as ⚠️ advisory mid-feature, ❌ blocking if this commit's own message or the plan doc claims the feature is complete. These are reachable the moment Spring wires them; a `@ConditionalOnProperty` bean can activate silently once its trigger property is set elsewhere in the same PR (e.g. Terraform)

**TypeScript**
- No `any` without an inline comment justifying it
- No default exports
- No `console.log` — pino only
- External input validated with `zod`
- No `.then()/.catch()` chains — async/await only
- No stub route handler left registered on a live route (same advisory/blocking split as the Java stub check above)

**Python**
- Type hints on all public function signatures
- Pydantic models for request/response
- `async def` for I/O-bound functions
- No mutable default arguments
- `structlog` only — not `print()` or `logging`
- No `raise NotImplementedError` left inside a FastAPI route handler or dependency-injected class (same advisory/blocking split as the Java stub check above)

---

## Phase 5 — ADR and code scan

**ADR check** — if this commit makes a non-trivial architectural decision (tech selection, trade-off, cost-impacting pattern):
- ADR committed alongside this change?
- ADR has all sections: Status, Date, Context, Decision, Alternatives Considered, Consequences (+ and −), Revisit when
- Implementing code cites the ADR number in a comment

**Plan alignment** — if the current branch matches `feat/<service>/<slug>`, look for `docs/03-services/<service>/plans/<slug>.md`. If found, read it and verify the staged diff implements what the plan describes — correct endpoint path, method, request/response shape, validation rules. Flag divergences as ⚠️ advisory with the specific plan section and the differing implementation. Do not flag empty methods, TODO bodies, or stub returns as plan divergences — those are mid-feature placeholders. (Phase 4's per-language stub check is a different concern — reachability, not plan conformance — and still applies.)

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
_Omit this section if any ❌ blocking issues are listed above. Resolve blockers first._
```
<imperative subject line ≤72 chars>

<body: why — omit if subject is self-explanatory>
```
---
