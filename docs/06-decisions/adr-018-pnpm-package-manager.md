# ADR-018: pnpm as Node.js Package Manager

**Status:** Accepted
**Date:** 2026-05-04

---

## Context

CollabSpace is a monorepo containing multiple Node.js services: Document Service, Realtime Service, and Notification Lambda. Each service has its own `package.json`. A package manager must be chosen before any Node.js service is scaffolded.

The choice of package manager has downstream effects on:

- How `node_modules` are structured and whether phantom dependencies are possible.
- CI/CD caching strategy and install speed.
- Whether a workspace protocol is used to share types or utilities across services.
- Lockfile format and reproducibility.

---

## Decision

Use **pnpm** (v10, current stable) as the package manager for all Node.js services and the monorepo root.

---

## Rationale

### Phantom dependency prevention

npm and yarn (classic) both hoist all dependencies into a flat `node_modules` at the root. This means a service can `import` a package that is listed as a dependency of one of its dependencies — not in its own `package.json`. These are called phantom dependencies.

Phantom dependencies are a class of bugs that are:

- Silent at development time (the import works).
- Breaking in production if the transitive dependency is removed or updated by the parent package.
- Extremely confusing to debug because the error appears in application code but the root cause is in `package.json`.

pnpm uses a content-addressable store with symlinks. Each package's `node_modules` only contains symlinks to its declared dependencies. Importing an undeclared package throws `MODULE_NOT_FOUND` immediately, catching phantom dependencies at development time rather than in production.

### Disk efficiency

pnpm stores all package files in a global content-addressable store (`~/.pnpm-store`) and creates hard links from the store into each project's `node_modules`. If two services use the same version of the same package, only one copy exists on disk — both services point to it via hard links.

For a monorepo with three Node.js services that share common dependencies (TypeScript, pino, zod), this meaningfully reduces disk usage in both the developer's local environment and CI runners.

### Workspace support

pnpm workspaces allow defining a `pnpm-workspace.yaml` at the monorepo root that declares which directories are workspace packages. This enables:

- `pnpm install` at the root installs all workspace packages.
- `pnpm --filter document-service run build` runs a script in a specific workspace.
- Shared internal packages (e.g., a `packages/shared-types` directory with TypeScript types shared across services) can be referenced with `workspace:*` in `package.json` without publishing to npm.

The shared-types use case is a concrete near-term need: Kafka event schemas consumed by both the Document Service (publisher) and the AI Assistant are best defined once and referenced by both.

### CI/CD speed

pnpm's content-addressable store caches at the file level, not the package level. CI cache hits are more granular — only changed packages are re-downloaded. Combined with pnpm's parallel installation, installs are consistently 2–3× faster than npm in CI environments.

### Lockfile

`pnpm-lock.yaml` records the exact resolved version of every dependency and transitive dependency, including peer dependencies. It is deterministic: the same lockfile always produces the same `node_modules`. It is also more readable than `package-lock.json` for code review purposes.

---

## Rejected alternatives

**npm (v10)**

npm is the default Node.js package manager and requires no installation beyond Node.js itself. It has the largest ecosystem familiarity. Rejected because:

- Flat `node_modules` allows phantom dependencies silently.
- No content-addressable store; each project gets full copies of packages.
- npm workspaces exist but the DX is less polished than pnpm workspaces.
- CI install speed is the baseline; no structural advantage.

npm is the right choice for a single-service project or when onboarding contributors who cannot be expected to have pnpm. For a monorepo where phantom dependencies are a real risk, pnpm is better.

**yarn (v4, Plug'n'Play)**

Yarn v4 with PnP mode is the most aggressive approach to phantom dependency elimination — it replaces `node_modules` entirely with a `.pnp.cjs` resolution map. It is fast and strict. Rejected because:

- PnP mode breaks tooling that assumes `node_modules` exists (some TypeScript language server features, some Jest configurations). Fixing these requires understanding the PnP resolution model.
- The zero-installs feature (committing the `.yarn/cache` to git) adds significant repo size.
- The learning curve for PnP is steeper than for pnpm, and the payoff relative to pnpm (which solves the same phantom-dependency problem) is marginal.

Yarn classic (v1) is not considered — it is in maintenance mode.

**Bun**

Bun is both a JavaScript runtime and a package manager. Its `bun install` is the fastest option by a significant margin. Rejected because:

- Using Bun as a package manager while running on Node.js (ADR-019) is an awkward split — the lockfile format (`bun.lockb`) is Bun-specific and binary.
- Bun as a runtime is not production-ready for all use cases (some Node.js APIs are partially implemented). Using it only for package management while running Node.js introduces friction without the runtime performance benefits.
- If the project ever moves to Bun as a runtime, both decisions can be made together. Splitting them creates inconsistency.

---

## Consequences

**Positive:**

- Phantom dependencies caught at development time, not production.
- Significant disk savings in local dev and CI due to content-addressable store.
- `pnpm-workspace.yaml` enables shared internal packages without a registry.
- Faster CI installs with granular caching.
- `pnpm --filter <service> run <script>` provides a clean per-service script interface from the monorepo root.

**Negative:**

- Requires pnpm to be installed (`npm install -g pnpm` or `corepack enable`). Contributors cannot use `npm install` — the `package.json` `engines` field and a root `.npmrc` with `engine-strict=true` should enforce this.
- Some global npm tools (`npx`, `npm exec`) are not available as `pnpm` equivalents without configuration. Use `pnpm dlx` as the equivalent of `npx`.
- The content-addressable store is in `~/.pnpm-store` by default. On CI, this path must be cached explicitly or installs will not benefit from the store.

---

## Implementation notes

Root-level files to create when scaffolding:

```
pnpm-workspace.yaml       # declares workspace packages
.npmrc                    # engine-strict=true, shamefully-hoist=false
package.json              # root: engines.node, scripts for workspace-wide commands
```

`pnpm-workspace.yaml` example:

```yaml
packages:
  - "services/*"
  - "packages/*"
```

`.npmrc` minimum:

```
engine-strict=true
shamefully-hoist=false
```

`shamefully-hoist=false` is the default and enforces strict isolation. Never set it to `true` — doing so reverts to npm-style hoisting and defeats the phantom dependency protection.

---

## Revisit when

- Bun reaches 1.0 stability for all Node.js APIs used in this project and the team wants to evaluate the full Bun runtime. At that point, evaluate switching runtime and package manager together.
- A shared internal package (`packages/shared-types`) grows large enough that the workspace build order matters. At that point, evaluate Turborepo or Nx for build orchestration on top of pnpm workspaces.
