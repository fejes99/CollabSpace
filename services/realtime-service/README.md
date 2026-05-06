# realtime-service

WebSocket-based real-time collaboration service for CollabSpace.

**Stack:** Node.js 24 · TypeScript · Fastify 5 · ws (Stage 2+) · Redis pub/sub (Stage 2+)

**Current stage:** Walking skeleton — health endpoint only

## Endpoints

| Method | Path      | Description                                 |
| ------ | --------- | ------------------------------------------- |
| `GET`  | `/health` | Health check — returns `{ "status": "ok" }` |

## Running locally

```bash
pnpm install
cp .env.example .env   # adjust values as needed
pnpm dev               # ts-node, serves on PORT (default 3001)
```

## Environment variables

| Variable    | Default       | Description                                                   |
| ----------- | ------------- | ------------------------------------------------------------- |
| `PORT`      | `3001`        | TCP port to listen on                                         |
| `LOG_LEVEL` | `info`        | Pino log level: `fatal` `error` `warn` `info` `debug` `trace` |
| `NODE_ENV`  | `development` | `development` enables pino-pretty transport                   |

All variables are validated at startup via zod — the process exits with a clear error if a required variable is missing or malformed.

## Tests

```bash
pnpm test       # node:test runner via ts-node, c8 coverage
pnpm typecheck  # tsc --noEmit
pnpm lint       # eslint src test
```

## Building for production

```bash
pnpm build      # tsc → dist/
pnpm start      # node dist/server.js
```

Or via Docker:

```bash
docker build -t realtime-service .
docker run -p 3001:3001 realtime-service
```

## Project structure

```
src/
  config/env.ts       — zod-validated env vars; process exits on bad config
  plugins/sensible.ts — @fastify/sensible (standard HTTP error helpers)
  routes/health.ts    — GET /health
  app.ts              — pure Fastify plugin; registers plugins + routes
  server.ts           — creates instance, calls listen(); production entry point
test/
  helper.ts           — buildApp() builds the app without listen(); used by all tests
  routes/health.test.ts
```

The `app.ts` / `server.ts` split keeps the Fastify instance out of tests — `buildApp()` in `test/helper.ts` creates a fresh instance via `app.inject()` without binding to a port.
