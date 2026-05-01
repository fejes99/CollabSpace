# Frontend Service

The CollabSpace frontend is a React single-page application (SPA) deployed to Vercel. It communicates with the backend exclusively through API Gateway (REST) and the ALB (WebSocket) — it never calls backend services directly. From the system's perspective, the frontend is treated as a service: it has its own stack documentation, its own CI/CD pipeline, and its own environment configuration.

Stack decisions are recorded in [ADR-016](../06-decisions/adr-016-frontend-stack.md). This document covers the implementation-level concerns: project structure, API communication, auth state management, WebSocket connection lifecycle, and the deployment model.

---

## Stack summary

| Concern | Choice |
|---|---|
| Build tool | Vite 8 |
| UI framework | React 19 |
| Router | React Router 7 |
| Server state | TanStack Query v5 |
| Client state | Zustand v5 |
| Forms | React Hook Form + zod |
| Styling | Tailwind CSS v4 |
| Component primitives | shadcn/ui (copied into `src/shared/ui/`) |
| WebSocket | Native WebSocket API |
| Auth client | Custom hooks |
| Testing | Vitest + React Testing Library + Playwright |
| Hosting | Vercel free tier |

---

## Project structure

The frontend uses **feature-based folder organisation**. Each feature is a self-contained directory containing its components, hooks, and local utilities. Shared code lives in `src/shared/` and is promoted there only when a piece is used by two or more features.

```
src/
├── features/
│   ├── auth/              — login, registration, token refresh, logout
│   ├── workspace/         — workspace creation, member management, settings
│   ├── document/          — document list, editor, document actions
│   ├── realtime/          — WebSocket connection, presence, live updates
│   └── ai/                — AI assistant chat, search
├── shared/
│   ├── ui/                — shadcn/ui components (Button, Dialog, Input, etc.)
│   ├── lib/               — utilities (date formatting, cn(), etc.)
│   └── api/               — HTTP client wrapper, request/response types
├── app/
│   ├── routes.tsx         — React Router route definitions
│   ├── providers.tsx      — root providers (QueryClient, Router, etc.)
│   └── main.tsx           — application entry point
└── env.ts                 — Zod-validated environment variable schema
```

**The promotion rule for `shared/`.** Code starts in the feature that first needs it. When a second feature needs the same code, it moves to `shared/`. This rule prevents two failure modes: duplication that diverges over time, and premature abstraction of code that only one feature ever uses. "Used by two or more features" is the only criterion — not "looks reusable" or "might be needed later."

Feature directories do not import from each other. If feature A needs something from feature B, that something belongs in `shared/`. Enforcing this makes the dependency graph of features a DAG with `shared/` at the leaves — removing or rewriting one feature does not require understanding another.

---

## Communicating with API Gateway

The frontend communicates with API Gateway using the native `fetch` API, wrapped in a thin client in `src/shared/api/`. The wrapper handles three cross-cutting concerns:

1. **Authorization header injection.** Every request (except the auth endpoints) includes `Authorization: Bearer <accessToken>`. The access token is held in memory — see [Auth state](#auth-state).
2. **Correlation ID injection.** Every request includes `X-Correlation-ID: <uuid>` generated at request time. If the request fails, this ID is logged to the browser console so it can be correlated against CloudWatch logs.
3. **Credentials inclusion.** All requests include `credentials: 'include'` so the HTTP-only refresh token cookie is sent to the auth endpoints.

The wrapper does not handle token refresh — that is the responsibility of TanStack Query's `retry` configuration and the auth hooks. If a request returns `401`, the auth hook attempts a silent refresh and retries once.

TanStack Query handles caching, background refetching, loading and error states, and stale-while-revalidate behaviour. Each feature defines its query keys and query functions. Mutations use `useMutation` and call `queryClient.invalidateQueries` on success to keep the cache consistent.

---

## Auth state

Auth state is managed with custom hooks in `src/features/auth/`. The design is deliberately manual — the goal is to understand every step of the JWT lifecycle, not to abstract it away.

### Token storage

The access token is stored **in memory only** — in a module-level variable or a React context, not in `localStorage` or `sessionStorage`. Memory storage means the token is lost on page refresh. On every page load, the frontend performs a silent refresh call to `/v1/auth/refresh` to obtain a fresh access token before rendering the authenticated application. If the refresh call fails (no valid cookie, expired refresh token), the user is redirected to the login screen.

`localStorage` is explicitly avoided because it is readable by any JavaScript executing on the page. XSS that reads `localStorage` can exfiltrate the access token. Storing the access token in memory limits an XSS attack to the current page session.

### Refresh flow

Silent refresh is triggered in two ways:

1. **On page load.** Before the authenticated layout renders, the app calls `POST /v1/auth/refresh`. If successful, the access token is stored in memory and rendering proceeds. If unsuccessful, the user is shown the login screen.

2. **Proactive refresh.** A timer is set to refresh the access token approximately 1 minute before it expires (at 14 minutes into the 15-minute lifetime). This prevents an in-progress user action from failing due to an expired token. The timer resets on every successful refresh.

The access token's `exp` claim is read to calculate the timer interval. No polling is used — a single `setTimeout` is set after each successful token acquisition.

### Logout

Logout calls `POST /v1/auth/logout`, which clears the server-side refresh token and blocklists the access token `jti`. Client-side, the access token is cleared from memory, TanStack Query's cache is cleared (to remove user-specific data), and the router redirects to the login screen.

---

## WebSocket connection

The frontend maintains a single WebSocket connection per session, managed by the `realtime` feature. The connection is established after authentication and maintained for the duration of the session.

### Connection lifecycle

1. After successful authentication (or silent refresh), the app opens a WebSocket connection to the ALB: `wss://realtime.collabspace.io/ws`.
2. The access token is sent in the initial connection handshake via a query parameter (`?token=<accessToken>`) or an upgrade header. The Realtime Service validates the token on connection. The specific mechanism is defined when the Realtime Service is implemented.
3. If the connection is closed unexpectedly, the client reconnects with exponential backoff (base: 1s, multiplier: 2, max: 30s, jitter: ±10%).
4. On token refresh, the WebSocket connection is re-established with the new token.

### Message handling

The Realtime Service sends `document.updated` events to all WebSocket clients in a workspace when a document is saved. The frontend receives these events and calls `queryClient.invalidateQueries({ queryKey: ['document', documentId] })` to trigger a background refetch of the affected document. This keeps the document list and any open editor views consistent with the server state.

The frontend does not apply received events directly to the local state without a server round-trip — this avoids the complexity of operational transform or CRDT-based conflict resolution, which is out of scope for v1.

---

## Environment configuration

Environment variables are validated at startup using a zod schema defined in `src/env.ts`. If a required variable is missing or malformed, the application throws an error immediately rather than failing silently at runtime.

```typescript
// src/env.ts
import { z } from 'zod';

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url(),
  VITE_WS_URL: z.string().url(),
  VITE_ENVIRONMENT: z.enum(['development', 'staging', 'production']),
});

export const env = envSchema.parse(import.meta.env);
```

All environment variables are prefixed with `VITE_` (Vite's convention for variables exposed to the browser bundle). They are defined in a `.env.local` file for local development (gitignored) and as Vercel environment variables for deployed environments.

Environment variable names are final at build time — they are statically replaced in the bundle by Vite. They are not secrets (they are in the browser bundle) and should not contain API keys, signing secrets, or anything that should not be public.

---

## Deployment

The frontend is hosted on Vercel. The deployment model is:

- Every push to `main` triggers a production deployment.
- Every pull request receives a preview deployment at a unique URL (e.g., `https://pr-123.collabspace-frontend.vercel.app`).
- Environment variables are configured in the Vercel project settings per environment (development, preview, production).

Vercel detects the Vite configuration automatically and runs `vite build` to produce a static build output. No server-side processing is involved — the output is a directory of static HTML, JS, and CSS assets served from Vercel's CDN.

The Vercel project is not managed by Terraform. It is provisioned manually and documented here. This is an intentional deviation from the "infrastructure as code" principle, justified by the Vercel free tier's minimal configuration surface and the low learning value of Terraform-managing a static host.

---

## Testing strategy

### Unit and component tests (Vitest + React Testing Library)

Component tests verify that components render the correct output for given props and that user interactions trigger the expected state changes. Tests use React Testing Library's `render` and user-interaction utilities (`userEvent`). Tests do not assert on implementation details (component internals, hook state) — they assert on what a user would see or interact with.

Tests for TanStack Query hooks mock the HTTP layer at the `fetch` level, not at the query client level, to keep the test environment close to production behaviour.

### E2E tests (Playwright)

Playwright tests cover critical user journeys end-to-end:

- Successful login → see workspace list
- Create a document → see it in the list
- Open a document in two browser tabs → save in one tab → verify update appears in the other (WebSocket path)
- Logout → verify protected routes are inaccessible

E2E tests run against the local dev environment (docker-compose infra + native services) and against the Vercel preview deployment in CI.
