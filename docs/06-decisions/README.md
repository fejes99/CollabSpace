# Architecture Decision Records

This directory contains the project's Architecture Decision Records (ADRs). Each ADR captures one non-trivial decision: the context that forced it, the choice made, the alternatives considered, and the consequences accepted.

ADRs are how the project answers the future question "why was this done this way?" without recovering the answer from memory or commit archaeology.

---

## When to write an ADR

A decision deserves an ADR if any of the following are true:

- It involves a real trade-off with consequences (cost, complexity, security, performance).
- It touches cost — the project is free-tier-maximalist; cost-affecting choices need explicit rationale.
- It selects a technology (a database, a framework, a hosting model).
- It defines a pattern others will follow (e.g. how every service does X).
- It would surprise a reader of the code (a lifecycle rule, a workaround, an unusual flag).

If none of these apply, do not write an ADR. ADRs derive their value from being decisions worth recording; cluttering the directory with trivia dilutes that.

---

## Conventions

- **Filename**: `adr-NNN-kebab-case-title.md`. Numbers are zero-padded to three digits: `adr-001`, `adr-024`. Always sequential — never reuse a retired number.
- **Required sections** (in this order):
  1. `## Status` — see Status values below.
  2. `## Date` — ISO date when the ADR was written.
  3. `## Context` — what forced the decision; the constraints in play.
  4. `## Decision` — the choice made, stated assertively.
  5. `## Alternatives considered` — what else was on the table and why each was rejected. The more honest this section, the more useful the ADR.
  6. `## Consequences` — `+` bullets for positive effects, `−` bullets for negative effects. Both lists must be non-empty; an ADR with no downsides is a decision that wasn't stress-tested.
  7. `## Revisit when` — the trigger condition that would prompt re-opening this decision.
- **Status values**:
  - `Proposed` — written but not yet acted on.
  - `Accepted` — implemented and in force.
  - `Superseded by ADR-NNN` — replaced by a later ADR; link to it.
  - `Deprecated` — no longer in force but no replacement.
- **Write at decision time, not later.** Retroactive ADRs miss the alternatives that were genuinely considered at the moment and rationalize the chosen path. Write the ADR before merging the change it documents.
- **Adversarial review before committing.** Ask Claude Code to poke holes in the decision first. Revise based on what surfaces. An ADR that hasn't been stress-tested is a story, not a decision record.

---

## How to read an ADR

Start with **Status**. If it's `Superseded`, jump to the replacement.

If it's `Accepted`, read **Decision** and **Consequences (−)** first — the *what* and the *cost*. The Context and Alternatives matter when you're considering changing the decision.

The **Revisit when** trigger is what makes ADRs not stale forever. If you hit a trigger condition in current work, the ADR explicitly invites you to re-open the decision.

---

## Cross-references

- For *how* a feature gets written (the workflow that produces ADRs), see [feature-workflow.md](../07-development/feature-workflow.md).
- For *who* makes which decisions and *what's in scope*, see [roadmap.md](../roadmap.md).
- For the per-language and per-tool conventions that ADRs justify, see [coding-standards.md](../07-development/coding-standards.md).
