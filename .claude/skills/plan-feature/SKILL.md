---
name: plan-feature
description: Interactive feature planner. Walks through Phase 0 (Slice) and Phase 1 (Plan) of the feature workflow. Produces an approved plan document at docs/03-services/<service>/plans/<slug>.md after adversarial self-review. Run at the start of every Full or Small tier feature.
disable-model-invocation: true
allowed-tools:
  - Read
  - Write
  - Bash(git status *)
  - Bash(git branch *)
  - Bash(ls *)
  - Bash(mkdir *)
  - Bash(test *)
  - AskUserQuestion
---

## Arguments

`<service> <slug>` — e.g. `/plan-feature auth user-registration`.

- `<service>` must be one of `auth`, `document`, `realtime`, `ai`, `notification`.
- `<slug>` is kebab-case, 2–4 words.

If either argument is missing or invalid, ask before proceeding.

---

## Live context

Branch: !`git branch --show-current`
Working tree: !`git status --short`

---

## Phase 1 — Session check

Before any planning work:

1. **`/start-session` evidence.** Confirm this conversation has loaded CLAUDE.md and produced a session brief. If not, stop and tell the user to run `/start-session` first — the plan needs the current stage and goal from Layer 2 to make tier and scope decisions correctly.

2. **Working tree state.** If there are uncommitted changes unrelated to plan creation, surface them and ask whether to stash or commit before proceeding. A plan doc landing on top of stale work is a source of merge confusion later.

3. **Service exists.** Verify `services/<service>/` exists. If not, stop.

4. **Plan does not already exist.** Verify `docs/03-services/<service>/plans/<slug>.md` does not exist. If it does, ask whether to overwrite, pick a new slug, or open the existing plan for revision.

---

## Phase 2 — Slice (workflow Phase 0)

Ask in sequence, one question at a time. Do not bundle.

1. **"What is the slice? One sentence."** Reject the answer and ask the user to split if the sentence contains "and" twice — that is two slices.
2. **"Three IN bullets — what this slice does."**
3. **"Three OUT bullets — what this slice explicitly does NOT do."**

Restate the slice + IN + OUT back to the user and confirm before continuing.

---

## Phase 3 — Tier check

Decide based on the slice and IN bullets:

- **Full** if any of: schema change, new dependency, >1 endpoint, ADR-worthy decision, auth/security touch.
- **Small** if all of: one endpoint, no schema change, no new dependency, no ADR-worthy decision.
- **Fast** is not a tier this skill handles — if the work is genuinely Fast (typo, dep bump, doc edit), stop and tell the user to skip planning and go straight to a branch.

State the recommendation and one-sentence reason. Ask the user to accept or override. If they override, ask why — and note the reason in the plan doc's status block as a comment.

---

## Phase 4 — Walk the plan sections

For **Full**, walk all 9 sections. For **Small**, walk sections 1, 2, 3, 5, 8, 9 (drop 4 Data model, 6 Edge cases table, 7 Authorization detail — those are not separately needed for a no-schema, no-auth change).

Ask sectional questions in plain prose. For genuinely multiple-choice questions (e.g. "Does this endpoint require auth, internal-only auth, or no auth?"), use AskUserQuestion.

### Section 1 — Slice statement
Already captured in Phase 2. Copy verbatim.

### Section 2 — User-visible behavior
"List what an external observer can verify. One bullet per observable behavior."

### Section 3 — API contract
Ask in sequence:
- HTTP method
- Path (must start with `/v1/`)
- Auth requirement (None / Bearer JWT / Internal-only — see [docs/02-architecture/api-gateway-trust.md](../../../docs/02-architecture/api-gateway-trust.md))
- Request body shape (rough JSON sketch, or "None")
- Response body shape (rough JSON sketch for happy path)
- Status codes for each non-happy path

### Section 4 — Data model changes
"List new tables, columns, indexes. Migration tool: <Flyway for Java; per-service decision for others>. 'None' if no schema change."

### Section 5 — Validation rules
"For each input field: constraint and error code. Reference [docs/02-architecture/api-conventions.md](../../../docs/02-architecture/api-conventions.md) for error format."

### Section 6 — Edge cases
"For each scenario where the happy path doesn't apply: expected status and response body."

Suggested categories to walk through: missing/empty inputs, malformed inputs, conflict (resource already exists), not found, wrong role, expired token, idempotent retry (if relevant).

### Section 7 — Authorization
"Who can call this? What JWT claim or membership is required? Behavior for unauthenticated and wrong-role requests. Reference [docs/02-architecture/authorization.md](../../../docs/02-architecture/authorization.md)."

### Section 8 — Observability
- Log lines emitted (event name, fields)
- Correlation ID propagation (read from header, attach to all log lines on this request)
- Audit events (if any — see [docs/02-architecture/authentication.md](../../../docs/02-architecture/authentication.md) §audit events for the standard table)

### Section 9 — Out of scope
Copy the OUT bullets from Phase 2 verbatim.

After all sections are collected, assemble the draft as a single markdown document and show it to the user. Ask:

> Does this draft accurately reflect what you intend to build?

Iterate until the user is satisfied.

---

## Phase 5 — Adversarial review

This phase is the highest-value step in the skill. Do not skip it.

After the user is happy with the draft, switch role: critique the plan ruthlessly as if reviewing someone else's design. Produce output in this exact shape:

> ## Adversarial review
>
> **Missing edge cases**
> - <case the plan doesn't handle>
>
> **API contract ambiguity**
> - <field whose meaning is unclear, missing status code, inconsistent naming>
>
> **Validation gaps**
> - <input the plan doesn't validate that it should>
>
> **Authorization holes**
> - <unauthenticated/wrong-role/cross-workspace behavior not specified>
>
> **Observability gaps**
> - <audit event missing, correlation ID untracked, log line not emitted for an important state change>
>
> **Scope creep risk**
> - <item in the IN list that probably belongs in OUT, given the slice statement>
>
> **Cross-document inconsistency**
> - <plan disagrees with authentication.md / authorization.md / api-conventions.md>
>
> **Other concerns**
> - <anything else>

Be specific. "Validation could be better" is useless; "the plan doesn't reject names longer than 100 chars, which will fail at the database varchar boundary" is useful.

If a category genuinely has no issues, write "_None_" under it rather than omitting it — the empty category is itself a signal that the plan covered that dimension.

Ask the user:

> Revise the plan to address these, or accept any as-is with justification?

Iterate draft + critique loop until the user declares the critique empty.

---

## Phase 6 — Write the file

Show the final draft as one block. Ask explicitly:

> Write this to `docs/03-services/<service>/plans/<slug>.md`?

Only on explicit yes:
- Verify the directory exists; create `docs/03-services/<service>/plans/` if not.
- Write the file.
- Confirm with the user that the file was created.

---

## Phase 7 — Next steps

After writing, output:

> Plan committed locally. Next steps from `feature-workflow.md` Phase 2:
>
> 1. `git checkout -b feat/<service>/<slug>`
> 2. Stage the plan doc + commit: `git add docs/03-services/<service>/plans/<slug>.md && git commit -m "Plan: <slug>"`.
> 3. Write the database migration first. Apply locally, roll back, apply again.
> 4. Stub controller / service / repository with empty methods and TODOs.
> 5. Commit stubs and push the branch as a **draft PR**. The CI will compile your stubs.
>
> Then move to Phase 3 of the workflow (happy path).

Stop here. Do not start implementing.

---

## Plan document structure

The output file follows this section order. Adjust per tier.

1. **Title block.** `# Plan: <feature title>` followed by `**Service:** ...`, `**Slug:** ...`, `**Tier:** Full | Small`, `**Status:** Draft`.
2. **§ 1 Slice statement** — one sentence.
3. **§ 2 User-visible behavior** — bulleted list of observable behaviors.
4. **§ 3 API contract** — small table with method/path/auth/request/response/status. Followed by JSON sketches for request and response, and an error table.
5. **§ 4 Data model changes** — migration sketch.
6. **§ 5 Validation rules** — table: field / constraint / error code / message.
7. **§ 6 Edge cases** — table: scenario / status / response.
8. **§ 7 Authorization** — prose paragraph + claim/role requirements.
9. **§ 8 Observability** — sub-bullets for log lines, correlation ID, audit events.
10. **§ 9 Out of scope** — bulleted list verbatim from Phase 2.

For Small tier: omit §§ 4, 6, 7 but keep their headings with `_Not applicable for this slice._` so the reader sees the categories were considered.

---

## Constraints

- Do not write the plan file until the user gives explicit approval in Phase 6.
- Do not skip Phase 5 (adversarial review). It is the highest-value step.
- Do not infer plan content from the codebase silently. Ask the user. The plan is a forcing function for the user to think, not for Claude to research.
- After writing the file, do not start implementing. Stop at Phase 7's "next steps" output.
- All section content comes from user answers, never invented. If the user gives a vague answer, ask for specifics rather than filling in.
