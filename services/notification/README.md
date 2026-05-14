# notification

Event-driven AWS Lambda function (Node.js 24) that handles outbound notifications for CollabSpace. In Stage 1 it serves a single health endpoint via ALB. Stage 2+ will consume SNS/SQS fan-out events and dispatch email and push notifications.

## Stack

| Concern     | Choice                         |
|-------------|--------------------------------|
| Runtime     | Node.js 24 (Lambda managed)    |
| Language    | TypeScript 5 (strict)          |
| Build       | esbuild (single-file bundle)   |
| Deployment  | ZIP → `aws lambda update-function-code` |
| Trigger     | ALB (health); SNS/SQS (Stage 2+) |

## Endpoints

| Method | Path                    | Description        |
|--------|-------------------------|--------------------|
| GET    | `/notifications/health` | Health check       |

## Environment variables

None required for the walking skeleton. Stage 2+ will add:

| Variable | Description |
|---|---|
| `LOG_LEVEL` | Structured log level (default `info`) |

## Local development

This service is a Lambda function — there is no local server. Run tests directly:

```bash
pnpm install
pnpm test
pnpm typecheck
pnpm lint
```

To produce the deployment artifact locally:

```bash
pnpm run package   # build + zip → function.zip
```

## Scripts

| Script           | Purpose                                    |
|------------------|--------------------------------------------|
| `pnpm build`     | Bundle `src/handler.ts` → `dist/handler.mjs` via esbuild |
| `pnpm zip`       | Zip `dist/` → `function.zip`              |
| `pnpm package`   | build + zip in one step                   |
| `pnpm test`      | Run unit tests with vitest                |
| `pnpm typecheck` | Type-check without emitting               |
| `pnpm lint`      | ESLint with TypeScript rules              |

## Project structure

```
services/notification/
├── src/
│   ├── handler.ts        # Lambda entry point
│   └── handler.test.ts   # Unit tests
├── dist/                 # Build output (gitignored)
├── function.zip          # Deployment artifact (gitignored)
├── package.json
├── tsconfig.json
└── eslint.config.js
```

## Deployment

Deployed automatically by `.github/workflows/service-notification.yml` on push to `main` when `services/notification/**` changes. The workflow builds, zips, and calls `aws lambda update-function-code` using OIDC credentials.
