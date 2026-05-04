# ADR-017: Fastify Over Express for Node.js Services

**Status:** Accepted
**Date:** 2026-05-04

---

## Context

CollabSpace has two Node.js + TypeScript services: the Document Service (REST API over MongoDB) and the Realtime Service (WebSocket coordination). Both were initially listed with Express as the web framework. Before scaffolding either service, the framework choice needs to be locked.

Express has been the default Node.js web framework for over a decade and has an enormous ecosystem. However, the Node.js ecosystem has matured significantly; newer frameworks offer substantive improvements that are relevant to this project:

- JSON serialization performance matters because the Document Service will handle large document payloads and frequent read operations.
- Schema validation at the framework boundary reduces boilerplate — without it, every route would need manual Zod schema invocation.
- TypeScript support quality affects development ergonomics in a strict-mode TypeScript project.
- The Realtime Service needs WebSocket support; framework WebSocket plugin quality matters.

---

## Decision

Use **Fastify** (v5, current stable) as the web framework for both the Document Service and the Realtime Service.

---

## Rationale

### Performance

Fastify is measurably faster than Express for JSON-heavy workloads. The core mechanism is Fastify's JSON serialization pipeline: routes declare a response JSON Schema, and Fastify compiles a fast-json-stringify serializer at startup instead of calling `JSON.stringify` at runtime. For a document service returning large JSON payloads, this is a real throughput difference, not a micro-benchmark artefact.

Express does not serialize JSON — it defers to `JSON.stringify` on every response. There is no mechanism to pre-compile a serializer.

### Built-in schema validation

Fastify validates request bodies, query strings, headers, and route parameters against JSON Schema before the handler executes. Validation failures return structured 400 errors without handler code.

This does not replace Zod. The layers are complementary:

- **Fastify schema validation:** validates the shape of HTTP input at the framework boundary (is the body a valid object with the expected fields?).
- **Zod:** validates business logic constraints (is this email already registered? is the document title within length limits?). Types are inferred from Zod schemas; there is no manual interface duplication.

### TypeScript support

Fastify is written in TypeScript and ships its own types. Route handlers are typed generically from the schema definition — the request body, query params, and response type are inferred without manual `req.body as SomeType` casts. This is compatible with `strict: true` throughout.

Express's types (`@types/express`) are a community-maintained overlay that does not fully exploit TypeScript generics. `req.body` is typed as `any` by default, requiring explicit casting at every handler.

### WebSocket support

`@fastify/websocket` integrates WebSocket handling into the Fastify plugin system. WebSocket routes are defined alongside HTTP routes on the same server instance, with the same plugin-encapsulated scope. This is directly relevant to the Realtime Service.

### Plugin encapsulation model

Fastify's plugin system uses `fastify.register()` with lexical scope encapsulation: plugins, decorators, and hooks registered inside a scope are invisible outside it. This enforces module boundaries at the framework level. For example, authentication middleware can be registered only on the routes that require it, rather than being globally applied and then selectively excluded.

This is a steeper learning curve than Express middleware, which is intentional — the encapsulation model teaches a pattern that scales to complex service graphs.

---

## Rejected alternatives

**Express (v5)**

Express v5 was released in October 2024 after a long beta. It is battle-tested, has the largest ecosystem, and requires the least learning. Rejected because:

- No native JSON schema validation; every route needs manual validation boilerplate.
- `req.body` is `any` by default; no route-level TypeScript inference.
- No meaningful performance improvement over v4 for JSON-heavy workloads.
- No built-in WebSocket support; would require `express-ws` or a separate `ws` server instance alongside Express.

Express is the right choice for a brownfield codebase with existing Express code and middleware. For a greenfield TypeScript service in 2026, Fastify is the better default.

**Hono**

Hono is an extremely fast, edge-runtime-first framework with excellent TypeScript support. Considered because of its performance characteristics and its `zod-validator` middleware.

Rejected because:

- Hono is designed for edge runtimes (Cloudflare Workers, Deno Deploy). Running it on Node.js/ECS is supported but is not its primary target — the documentation and ecosystem are oriented around edge deployment.
- WebSocket support on Node.js requires additional configuration that is less mature than `@fastify/websocket`.
- The learning resources for Node.js + Hono are thinner than for Fastify.

**NestJS**

NestJS is a full application framework with dependency injection, decorators, modules, and conventions borrowed from Angular. Rejected because:

- The abstraction overhead is significant for services at this scale. The DI container, module system, and decorator-based routing add learning complexity that is orthogonal to the goals of this project.
- CLAUDE.md Library Policy: prefer manual DI / awilix over heavy DI containers in TypeScript.
- NestJS can use either Express or Fastify as its underlying adapter — learning NestJS defers learning the underlying transport, which is the opposite of what this project wants.

---

## Consequences

**Positive:**

- Faster JSON serialization for document-heavy payloads without code changes.
- Request validation at the framework boundary reduces handler boilerplate.
- Full TypeScript inference on routes with `strict: true`.
- Plugin encapsulation enforces service module boundaries explicitly.
- `@fastify/websocket` unifies HTTP and WebSocket handling in one server process.
- `@fastify/swagger` generates OpenAPI specs from route schemas without separate annotation.

**Negative:**

- Fastify's plugin encapsulation model (scope, `fastify-plugin`, `decorate`) has a learning curve. Middleware that works in Express does not translate directly.
- Smaller ecosystem than Express. Not every Express middleware has a Fastify equivalent; some require writing a thin Fastify plugin wrapper.
- JSON Schema (Fastify's validation format) and Zod have overlapping but different syntax. The two layers must be kept in sync manually — schema changes need updates in both places until a codegen step is added.
- CLAUDE.md code style currently references Express patterns; those references must be updated (done in the same session as this ADR).

---

## Revisit when

- The Realtime Service moves to a dedicated WebSocket server separate from the Document Service. At that point, evaluate whether Fastify's HTTP surface is still needed for the Realtime Service or whether a bare `ws` server with no HTTP framework is simpler.
- Hono matures significantly on Node.js and its edge-first assumptions no longer apply. At that point, re-evaluate if its performance characteristics justify a migration.
