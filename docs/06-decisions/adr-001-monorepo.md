# ADR-001: Monorepo over Polyrepo

**Status:** Accepted  
**Date:** 2026-04-25

---

## Context

CollabSpace consists of five services written in three languages (Java,
TypeScript, Python) plus Terraform infrastructure and a documentation tree.
A repository structure decision was required before any code was written.

The project is built by a single developer whose primary goal is learning.
The honest drivers for monorepo over polyrepo are two:

1. **Lower setup overhead.** Five separate repos, five CI pipelines, five
   clones, and cross-repo atomic commits requiring coordinated PRs add
   complexity that slows learning without adding value at this stage.

2. **Atomic cross-service commits when event schemas change.** Services
   communicate through REST and broker events. The only scenario requiring
   a genuinely atomic multi-service commit is updating a broker event schema
   and its consumers simultaneously. A monorepo makes this a single PR;
   polyrepo requires coordinated PRs with ordering constraints.

A secondary concern is boundary enforcement. In a monorepo, services are
one import statement away from each other. Without an explicit rule, casual
cross-service imports are the path of least resistance — producing a
distributed monolith disguised as microservices.

---

## Decision

Use a single monorepo for all services, infrastructure, and documentation.

Directory layout:
- `/services/<name>/` — one directory per service, fully self-contained
- `/infrastructure/` — Terraform modules and environment compositions
- `/docs/` — architecture documentation and ADRs
- `/shared/contracts/` — JSON schemas for broker events only (no executable
  code; each service owns its own validator implementation)
- `/.github/workflows/` — per-service CI workflows with path filtering

Service independence is enforced by convention and tooling:
- Cross-`/services/` imports are banned via per-language lint rules
  (`eslint import/no-restricted-paths` for TypeScript, `ruff` rules for
  Python, Checkstyle for Java). These rules must pass in CI — a failed
  lint check blocks merge. Any inline suppression (`eslint-disable`,
  `# noqa`, `@SuppressWarnings`) requires a comment explaining why the
  violation is justified; suppressions without justification are treated
  as a code smell in review.
- `/shared/contracts/` contains schema files only (JSON Schema or Avro).
  It is a **design-time source of truth** — it ensures all services start
  from the same schema definition. It does not enforce runtime consistency:
  each service owns its own validator implementation, and those validators
  can drift from the schema without a build failure. Runtime consistency is
  each service's responsibility. A schema registry would solve this but is
  out of scope at the $0–5/month cost target.
- Any code that "wants" to be shared across services must be promoted to a
  versioned package and evaluated against the boundary rule explicitly.

---

## Alternatives Considered

### Polyrepo (one repository per service)

Genuine strengths that were weighed:

- **Independent CI/CD lifecycles.** Each service deploys on its own
  cadence with no risk of an unrelated change blocking a release.
- **Repo-level access control.** A second developer can be given ownership
  of one service without read access to others.
- **Language-isolated tooling.** No risk of a root-level config (ESLint,
  `.editorconfig`) conflicting across language boundaries.
- **Cleaner clone story at scale.** A new team member working only on the
  Document Service does not need the full history of all services.

Rejected because: none of these benefits are material for a single
developer with no access control requirements and no independent release
cadences. The overhead adds complexity that slows learning without adding
value at this stage.

---

## Consequences

**Positive:**
+ Single clone, single IDE workspace, single `git log` across all services
+ Atomic commits when a change touches multiple services simultaneously —
  the primary practical case is updating a broker event schema and its
  consumers in one PR
+ Documentation and infrastructure colocated with service code, easier
  to keep in sync as the system evolves
+ `/shared/contracts/` provides a design-time single source of truth for
  event schemas across three languages without a package registry
+ Path-filtered CI gives per-service isolation without per-service repos

**Negative:**
− Cross-service imports are technically possible; enforcement requires lint
  rules wired into CI plus review discipline around suppressions. Boundary
  erosion is an ongoing risk, not a solved problem
− `/shared/contracts/` enforces design-time schema consistency only;
  runtime validator drift across services is undetected without a schema
  registry
− Monorepo CI is more complex than per-service CI: path filtering must be
  maintained as new directories are added
− If a second developer joins, they receive read access to all service code
  by default; granular access control requires splitting repos at that point

---

## Revisit When

- A second developer joins with primary ownership of a single service
  (access control becomes a real requirement)
- A service needs independent open-source distribution
- CI runtime exceeds 15 minutes for typical PRs even with path filtering
- Cross-`/services/` import lint violations become routine
  (signal that boundaries are eroding in practice, not in principle)
