# Pre-Commit Checklist

Run through this before every commit. Sections are conditional — only apply the ones relevant to what changed. The Always section applies unconditionally.

---

## Always

### Staged files

- [ ] No `.env` files staged. Run `git diff --cached --name-only` and verify. Even a `.env.example` must not contain real values.
- [ ] No hardcoded secrets, AWS account IDs, access keys, tokens, or passwords anywhere in the diff. Check comments too — secrets in comments are still secrets.
- [ ] No debug artifacts left in: `console.log`, `System.out.println`, `print()`, `debugger`, commented-out code blocks that are not intentionally preserved.
- [ ] No `TODO` or `FIXME` that was supposed to be resolved in this commit. If a TODO is intentional and long-lived, it should reference an issue or ADR, not be left bare.

### Commit message

- [ ] Written in imperative mood: "Add health check endpoint", not "Added health check endpoint".
- [ ] Describes **why**, not just what. The diff already shows what changed. The message should explain the motivation or constraint that drove the change.
- [ ] Atomic: this commit does one thing. If the message needs "and" to describe it, consider splitting.
- [ ] References the ADR or issue number if the change implements a recorded decision (e.g., `Implement ECS public-subnet strategy (ADR-009)`).

### Branch

- [ ] You are not on `main` unless the change is infrastructure in Stage 0 (see `CLAUDE.md` branching note in memory). For all other work: feature branch + PR.

---

## Documentation

This section is the most important to get right. Documentation debt compounds quickly in a multi-layer project. Apply every sub-section that touches something that changed.

### CLAUDE.md — Layer 2 (Current Focus)

Update this file whenever the project state changes. It is the source of truth for the current session context.

- [ ] **Completed items**: Did this commit finish something listed under "Next milestone" or complete a task? Move it to the `Completed:` list.
- [ ] **Next milestone**: Does the "Next milestone" block still describe what actually comes next, or does it need updating now that this work is done?
- [ ] **Current goal**: If the stage or goal has shifted, update the `Current goal:` line.
- [ ] **Out of scope**: If the scope has been redefined, update it.
- [ ] **Blocked on**: Add or remove blockers as they appear or are resolved.
- [ ] **Recent ADRs**: If a new ADR was written, add it to the `Recent ADRs:` line.
- [ ] **Pointers (Layer 3)**: If a new module, service, or doc file was created, add a pointer line so future sessions can navigate directly to it.

### Root README.md

- [ ] **Status block**: Does the stage and the "currently live" description still match reality? Update it to reflect what is actually deployed after this commit.
- [ ] **Tech Stack table**: If a runtime version, framework, or service changed, update the relevant row.
- [ ] **Docs index**: If a new `docs/` directory or file was created that a reader should know about, add it to the Docs section.

### infrastructure/README.md

- [ ] **Module table**: If a new Terraform module was added, add a row. If an existing module was renamed or removed, update or remove the row. Every row in this table must link to a `README.md` inside that module directory.
- [ ] **Dev environment description**: Does the paragraph describing what `environments/dev/` currently contains still reflect the live state?

### Module READMEs (`infrastructure/modules/*/README.md`)

Apply this if any `.tf` file inside a module changed.

- [ ] **What it creates table**: Does it list every resource the module actually creates? If a resource was added or removed, the table must be updated.
- [ ] **Inputs table**: Does it reflect the current `variables.tf`? New variable = new row. Removed variable = removed row. Changed description or default = updated cell.
- [ ] **Outputs table**: Does it reflect the current `outputs.tf`? Same rule.
- [ ] **Usage example**: Is the example still valid? If a required variable was added, the example must include it. A broken example is worse than no example.
- [ ] **Decision explanations**: If a new non-obvious design choice was made inside the module (a lifecycle rule, a specific flag, a workaround), it needs a "Why X?" paragraph. Do not leave surprising behaviour unexplained.
- [ ] **New module, no README**: If a new module was created and has no README, write one before committing. An undocumented module is a trap for future sessions. Use the existing module READMEs as a template (What it creates → Usage → Inputs → Outputs → any Why sections needed).

### environments/dev/README.md

- [ ] **What it creates table**: If a module call was added, removed, or changed in `main.tf`, update the table row. The Notes column must reflect the current state — remove "placeholder" language once the real thing is deployed, remove "when X is built" once X exists.
- [ ] **Not created here section**: If something moved from "not yet created" to "created", remove it from this list.
- [ ] **Cost table**: If a new resource was added that has a non-zero cost (ALB, NAT Gateway, RDS, etc.), add a row. The goal is that someone can read this table and know what they're paying for before they apply.
- [ ] **What comes next**: Update this list to reflect the actual next milestone. Remove items that are done. Add new items that have become clear.

### .github/workflows/README.md

- [ ] **Active workflows table**: If a new workflow file was created and is live, add a row with `Live` status. If an existing workflow was changed, update its Purpose description.
- [ ] **Planned workflows table**: If a planned workflow was implemented, move it from Planned to Active and change its Status. If a new planned workflow was identified, add it with `Planned` status.
- [ ] If a workflow was deleted, remove it from the table entirely.

### Service READMEs (`services/*/README.md`)

Apply this when any service code changed.

- [ ] Does the README reflect what the service currently does? If an endpoint was added or removed, update it.
- [ ] If the service was first scaffolded in this commit, create the README. It must cover at minimum: what the service does, how to run it locally, environment variables it reads, and how to run its tests.
- [ ] If the OpenAPI spec changed, verify the README links to or describes the updated contract.

### docs/06-decisions/ (ADRs)

See the dedicated ADR section below.

---

## If an architectural decision was made

A decision is non-trivial if any of these are true: it involves a trade-off with real consequences, it touches cost, it selects a technology, it defines a pattern others will follow, or it would surprise a reader of the code.

- [ ] **ADR written**: Create `docs/06-decisions/adr-NNN-kebab-case-title.md` with the next sequential number. Required sections: Status, Date, Context, Decision, Alternatives Considered, Consequences (with + and − bullets), Revisit when.
- [ ] **Adversarial review done**: Before committing the ADR, ask Claude Code to poke holes in the decision. Revise based on what that surfaces. Do not commit an ADR that has not been stress-tested.
- [ ] **Status is correct**: `Proposed` if it has not been acted on yet. `Accepted` if it is implemented in this commit. If this commit supersedes an older decision, update the old ADR's status to `Superseded by ADR-NNN`.
- [ ] **CLAUDE.md updated**: The new ADR number is added to `Recent ADRs:` in Layer 2.
- [ ] **Code cites the ADR**: If the decision is reflected in code (a lifecycle rule, a flag, a specific pattern), the relevant comment in the code should reference the ADR number.

---

## If Terraform changed

- [ ] `terraform fmt` run and output is clean (no diffs).
- [ ] `terraform validate` passes for every root module that was touched.
- [ ] All new resources have the required tags: `Environment`, `Service` (where applicable), `ManagedBy = "terraform"`.
- [ ] `for_each` used instead of `count` for any resource that creates multiple instances.
- [ ] No hardcoded account IDs, region strings, or ARNs. Use variables or data sources.
- [ ] If a new variable was added to a module, its description in `variables.tf` is complete and accurate. The description is what appears in `terraform plan` output — vague descriptions make plans unreadable.
- [ ] If a root module (`environments/dev/`) changed: does `environments/dev/README.md` reflect the change? (See Documentation section.)
- [ ] `terraform plan` reviewed before commit if the change touches live infrastructure. The plan output should match intent — no unexpected destroys or replacements.

---

## If Java (Spring Boot) changed

- [ ] `./mvnw test` passes.
- [ ] No `@Autowired` on fields. Constructor injection only.
- [ ] DTOs use records, not classes with getters/setters.
- [ ] `Optional<T>` used instead of returning `null`.
- [ ] `@Transactional` present on service methods that span more than one repository call.
- [ ] Controller inputs validated with `jakarta.validation` annotations — validation at the boundary, not inside service methods.
- [ ] No direct database access from controllers. All persistence goes through the service layer.
- [ ] New log statements use the project's structured logger (not `System.out.println`). Correlation ID is passed through if this is a request-scoped operation.

---

## If TypeScript (Node.js) changed

- [ ] `npm run lint` passes (zero errors, zero warnings).
- [ ] `npm run build` passes with `strict: true` in `tsconfig.json`.
- [ ] `npm run test` passes.
- [ ] No `any` types without an inline comment explaining why it is unavoidable.
- [ ] No default exports. Named exports only.
- [ ] No `console.log`. Use `pino` logger.
- [ ] All external input (request bodies, query params, env vars) validated with `zod` at the boundary. Types inferred from schemas, not written manually.
- [ ] `async/await` used throughout. No raw `.then()/.catch()` chains.

---

## If Python (FastAPI) changed

- [ ] `pytest` passes.
- [ ] `ruff check .` passes (zero errors).
- [ ] `black --check .` passes (no formatting diffs).
- [ ] Type hints on every public function signature (parameters and return type).
- [ ] Request/response models use Pydantic. No raw dicts crossing API boundaries.
- [ ] I/O-bound functions use `async def`.
- [ ] No mutable default arguments (`def f(x=[])` — Python reuses the same list across calls).
- [ ] New log statements use `structlog`, not `print()` or `logging.info()`.

---

## Definition of Done (for completed service features)

Apply this when a feature is considered finished, not just for in-progress commits.

- [ ] Unit tests cover the happy path and at least one failure/edge case.
- [ ] At least one integration test exercises the feature end-to-end.
- [ ] OpenAPI spec updated and reflects the current contract (auto-generated where possible).
- [ ] Service README updated to describe the new behaviour.
- [ ] Feature is deployed to dev via CI/CD — not just committed, actually running in AWS.
- [ ] Structured logs with correlation ID are present on all new code paths.
- [ ] ADR written if a non-trivial decision was made as part of this feature.

---

## Final check

Before running `git commit`:

```bash
git diff --cached --stat          # confirm staged files match intent
git diff --cached                 # one last read of the actual diff
git log --oneline -5              # check recent commit style for consistency
```

Ask: "If I came back to this codebase in three months with no context, would this commit — and the documentation it leaves behind — be enough to understand what changed and why?"
