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

1. **Layer 2 loaded.** Read `CLAUDE.md` Layer 2 now. If `Current stage` is blank or unset, stop and tell the user to set it before planning — tier and scope decisions depend on the current stage. If it is set, continue.

2. **Working tree state.** If there are uncommitted changes unrelated to plan creation, surface them and ask whether to stash or commit before proceeding. A plan doc landing on top of stale work is a source of merge confusion later.

3. **Service exists.** Verify `services/<service>/` exists. If not, stop.

4. **Plan does not already exist.** Verify `docs/03-services/<service>/plans/<slug>.md` does not exist. If it does, ask whether to overwrite, pick a new slug, or open the existing plan for revision.

5. **Fast-tier gate.** Ask: "Does this change touch service code, database schema, API contract, or introduce a new library/dependency?"
   - **No — it's a README / doc / skill / config change:** stop and say "This is Fast-tier work. Create a branch directly (`git checkout -b fast/<service>/<slug>`) and commit when done. No plan doc needed."
   - **No — it's a new ADR:** stop and say "New ADRs follow their own process. See the ADR section in `docs/07-development/commit-checklist.md`."
   - **Yes:** continue to Phase 2.

---

## Phase 2 — Slice (workflow Phase 0)

Ask one question at a time. Display the question, then stop. Wait for the user's answer before displaying the next question. Do not preview or number upcoming questions in the same message.

1. **"What is the slice? One sentence."** Reject the answer and ask the user to split if the sentence contains "and" twice — that is two slices.
2. **"Three IN bullets — what this slice does."**
3. **"Three OUT bullets — what this slice explicitly does NOT do."**

Restate the slice + IN + OUT back to the user and confirm before continuing.

---

## Phase 3 — Tier check

Decide based on the slice and IN bullets:

- **Full** if any of: schema change, new dependency, >1 endpoint, ADR-worthy decision, new auth mechanism, new RBAC rule, new JWT claim, or changes to the authentication/authorization flow. A standard JWT-protected endpoint that validates an existing claim is not Full-tier on that basis alone.
- **Small** if all of: one endpoint, no schema change, no new dependency, no ADR-worthy decision.
- **Fast** work is caught at Phase 1 check 5 and never reaches this phase.

State the recommendation and one-sentence reason. Ask the user to accept or override. If they override, ask why — and note the reason in the plan doc's status block as a comment.

---

## Phase 4 — Walk the plan sections

For **Full**, walk all 9 sections. For **Small**, walk sections 1, 2, 3, 5, 8, 9 (drop 4 Data model, 6 Edge cases table, 7 Authorization detail — those are not separately needed for a no-schema, no-auth change).

Ask sectional questions in plain prose. For genuinely multiple-choice questions (e.g. "Does this endpoint require auth, internal-only auth, or no auth?"), use AskUserQuestion.

**Before starting Section 3**, read in parallel:
- `docs/02-architecture/api-conventions.md`
- `docs/02-architecture/api-gateway-trust.md`

**Before starting Section 7** (Full tier only), read in parallel:
- `docs/02-architecture/authorization.md`
- `docs/02-architecture/authentication.md`

These reads are required for Phase 5's Cross-document inconsistency check to produce accurate findings rather than invented ones.

### Section 1 — Slice statement
Already captured in Phase 2. Copy verbatim.

### Section 2 — User-visible behavior
_Why this matters: if you can't describe observable behavior from the outside, you don't fully understand what you're building. This section is your definition of success before a line of code exists._
"List what an external observer can verify. One bullet per observable behavior."

### Section 3 — API contract
_Why this matters: defining the contract before the implementation prevents building code that works internally but is inconsistent or hard to use. Changes to an API after consumers exist are painful — getting it right on paper is free._
Ask in sequence:
- Path (must start with `/v1/`)
- HTTP method
- Auth requirement (None / Bearer JWT / Internal-only — see [docs/02-architecture/api-gateway-trust.md](../../../docs/02-architecture/api-gateway-trust.md))
- Request body shape (rough JSON sketch, or "None")
- Response body shape (rough JSON sketch for happy path)
- Status codes for each non-happy path

### Section 4 — Data model changes
_Why this matters: schema is the hardest thing to change after the fact. A wrong column type or missing index discovered in production means a migration on live data. Getting it right on paper costs minutes; getting it wrong costs migrations, downtime, and data bugs._
"List new tables, columns, indexes, and the migration approach. 'None' if no schema change."

_Internal reference — use this to answer migration-tool questions; do not read aloud:_
- **auth-workspace** (Java / Postgres): Flyway
- **ai-assistant** (Python / Postgres): Alembic — if not yet adopted for this service, flag as ADR-worthy before continuing
- **document-service** (Node.js / MongoDB): no migration tool defined — if the slice needs schema changes, flag as ADR-worthy before continuing
- **realtime-service / notification**: no persistent schema — flag if a schema change is proposed here

### Section 5 — Validation rules
_Why this matters: validation gaps are the most common source of 500 errors in production and the easiest to prevent. Every input field has a constraint — name them now, before code exists, or they get discovered by users._
"For each input field: constraint and error code. Reference [docs/02-architecture/api-conventions.md](../../../docs/02-architecture/api-conventions.md) for error format."

### Section 6 — Edge cases
_Why this matters: this is where junior vs senior thinking diverges most visibly. A junior asks "does the happy path work?" A senior asks "what are all the ways this can fail?" Define the failure modes before writing code so your tests have something to verify against._
"For each scenario where the happy path doesn't apply: expected status and response body."

Suggested categories to walk through: missing/empty inputs, malformed inputs, conflict (resource already exists), not found, wrong role, expired token, idempotent retry (if relevant).

### Section 7 — Authorization
_Why this matters: authorization bugs are invisible in local testing and catastrophic in production — they let the wrong person see or change someone else's data. Defining who can call this and what happens when they can't, before the code exists, is the only reliable way to get it right._
"Who can call this? What JWT claim or membership is required? Behavior for unauthenticated and wrong-role requests. Reference [docs/02-architecture/authorization.md](../../../docs/02-architecture/authorization.md)."

### Section 8 — Observability
_Why this matters: if something breaks at 2am and you have no logs, you're debugging blind in production. Define what you'll want to see before you need it — the correlation ID, the event name, the key fields — so the logs are there when you need them._
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

For every non-_None_ finding, add one sentence explaining the production consequence — what actually breaks, when, and for whom. "The plan doesn't validate email format" is incomplete; "the plan doesn't validate email format — an invalid email reaches the database and possibly the email provider, producing garbage data and silent send failures" is what teaches.

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

---

## Phase 7 — Next steps

After writing, output the following. Adapt the implementation sequence to the tier and whether a schema change was identified in the plan.

> Plan written. Create your branch and stage it:
>
> `git checkout -b feat/<service>/<slug>`
> `git add docs/03-services/<service>/plans/<slug>.md && git commit -m "Plan: <slug>"`
>
> **Now you implement.** Work through these phases yourself — ask for a nudge if stuck on a specific step, not for the solution:
>
> _(Full tier with schema change)_
> 1. Write the migration first. Apply it locally, roll it back, apply again. This proves recovery before any logic exists.
> 2. Stub the controller, service, and repository — signatures, types, and TODOs only. Compile. Commit. Push as a draft PR.
> 3. Write the integration test for the happy path **before** wiring the logic. Run it — it should fail. That failing test is your target.
> 4. Wire the happy path until the test passes. Commit.
> 5. For each row in your edge-cases table: write the test first, then make it pass.
>
> _(Full tier, no schema change / Small tier)_
> 1. Stub the controller and service — signatures, types, TODOs only. Compile. Commit. Push as a draft PR.
> 2. Write the integration test for the happy path first. Run it — it should fail.
> 3. Wire the happy path until the test passes. Commit.
> 4. For each edge case in the plan: write the test first, then make it pass.
>
> A nudge is "try X" — not the code. Ask: "I tried X, it's doing Y, I expected Z."

Stop here. Do not start implementing.

---

## Constraints

- Do not infer plan content from the codebase silently. Ask the user. The plan is a forcing function for the user to think, not for Claude to research.
- All section content comes from user answers, never invented. If the user gives a vague answer, ask for specifics rather than filling in.
