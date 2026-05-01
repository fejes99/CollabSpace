# ADR-016: Frontend Stack

**Status:** Accepted
**Date:** 2026-05-01

---

## Context

CollabSpace requires a browser-based frontend that communicates with the backend via REST (API Gateway) and WebSocket (ALB), handles JWT-based authentication client-side, and delivers real-time document collaboration. The stack decision is made before any frontend code is written, during Stage 1, to ensure documentation and CI/CD design can account for the frontend as a service alongside the backend.

Key constraints:
- No server-side rendering requirement. All data is user-specific and fetched post-authentication; there is no public, indexable content that benefits from SSR.
- The frontend developer has React experience but not recent experience with the current React ecosystem (React 19, Server Components, App Router, etc.). The stack should minimize the gap between documented learning resources and the tools used.
- Hosted on Vercel free tier. Static export or SPA deployment (not a Node.js server process) is preferred for cost and simplicity.
- Auth is implemented manually. No third-party auth library (Auth.js, Clerk, etc.) — the goal is to understand the JWT + cookie flow explicitly.

---

## Decision

| Concern | Choice |
|---|---|
| Build tool | Vite 8 |
| UI framework | React 19 |
| Router | React Router 7 |
| Server state | TanStack Query v5 |
| Client state | Zustand v5 |
| Forms | React Hook Form + zod |
| Styling | Tailwind CSS v4 |
| Component primitives | shadcn/ui (copy-paste, not a dependency) |
| WebSocket | Native WebSocket API |
| Auth client | Custom hooks (no third-party auth library) |
| Testing | Vitest + React Testing Library + Playwright (E2E) |
| Hosting | Vercel free tier |

---

## Rationale by concern

### Build tool — Vite

Vite has become the standard build tool for React SPAs. It provides near-instant dev server startup via native ES module serving (no bundling during development), fast HMR, and a Rollup-based production build. The documentation is excellent and the ecosystem (Vitest, plugins) is mature.

### React 19

React 19 is the current stable release and the version covered by current documentation and tutorials. It introduces the React Compiler (stable in 19.x), which automatically applies memoization — eliminating the need for manual `useMemo`/`useCallback` for performance. The key React 19 features used in this project are standard hooks and the concurrent renderer; Server Components and Server Actions are not used (see Next.js rejection below).

### React Router 7

React Router 7 is the current stable release of the most widely used React router. It provides nested routes, URL-based state management, and data loading patterns that are well-documented and widely understood. React Router 7 introduced optional file-based routing and a Remix-style loader/action pattern; this project uses the standard programmatic routing approach to stay close to documented fundamentals.

### TanStack Query v5

Server state (data fetched from the API) is managed by TanStack Query. TanStack Query handles caching, background refetching, loading/error states, and optimistic updates — concerns that are expensive to implement correctly with raw `useEffect` + `useState`. It pairs naturally with the REST API pattern and reduces the amount of manual cache invalidation logic in the application.

The distinction between server state and client state is important and worth internalising: server state is data that lives on the server and is fetched asynchronously; client state is application state that lives only in the browser (UI state, form state, selected tab, etc.). Mixing them into a single global store (the Redux pattern) creates unnecessary complexity.

### Zustand

Client state (UI state not derived from the server) is managed by Zustand. Zustand is a minimal, hooks-based state library with no boilerplate. It is appropriate for the small amount of true client state in this application: current workspace selection, WebSocket connection status, UI preferences.

### React Hook Form + zod

Form validation uses React Hook Form for form state management and zod for schema definition and validation. This matches the backend validation philosophy — zod is also used in the TypeScript services for runtime validation, and shared schema definitions between frontend and backend are a natural future extension. React Hook Form avoids controlled components, which keeps re-renders minimal on input change.

### Tailwind CSS v4

Tailwind CSS v4 is the current stable release with a redesigned configuration model (CSS-first configuration, no `tailwind.config.js` required). Utility-class styling is chosen over CSS Modules or CSS-in-JS because it collocates style decisions with markup, eliminates the mental overhead of naming CSS classes, and produces smaller bundles than most CSS-in-JS solutions.

### shadcn/ui

shadcn/ui is not a component library in the traditional sense — components are copied into the project source tree rather than installed as a dependency. This means the components are owned by the project and can be modified freely. shadcn/ui components are built on Radix UI primitives (unstyled, accessible) and styled with Tailwind. The copy-paste model is preferable to a black-box dependency for a project where learning the component implementation is part of the goal.

### Native WebSocket API

The native `WebSocket` browser API is used directly rather than Socket.IO or a similar abstraction. This matches the backend choice (the `ws` library rather than Socket.IO) and keeps both ends of the connection model visible. Reconnection logic, heartbeat, and message framing are implemented explicitly — this is the point.

### Custom auth hooks

Authentication state (current user, access token, refresh flow) is managed with custom React hooks rather than a third-party auth library (Auth.js, Clerk, etc.). The goal of this project includes understanding the token lifecycle: how the access token is held in memory, how the refresh call is triggered silently before expiry, how the logout flow clears state. A third-party library abstracts exactly the part that is most valuable to understand.

### Testing

- **Vitest:** Jest-compatible test runner with native Vite integration. Faster than Jest for Vite projects.
- **React Testing Library:** renders components in a jsdom environment and provides queries that match how users interact with the UI. Avoids testing implementation details.
- **Playwright:** E2E browser testing for critical user journeys (login, document create, real-time collaboration).

### Hosting — Vercel

Vercel's free tier provides push-to-deploy from GitHub, preview deployments on pull requests, and CDN-distributed static hosting. For a frontend with no server-side rendering, Vercel is zero-configuration for a Vite project and removes the need to manage an S3 bucket + CloudFront distribution.

The alternative — S3 + CloudFront — was evaluated and rejected. It would require Terraform configuration for the CloudFront distribution, an OAI/OAC policy, a bucket policy, and a custom domain certificate. This is non-trivial infrastructure that adds operational complexity without educational return specific to the frontend. The learning goal for frontend hosting is "push to deploy"; the learning goal for S3/CloudFront can be addressed by other means if needed.

---

## Rejected alternatives

**Next.js (App Router)**

Next.js is the most popular React meta-framework and would be a reasonable production choice. Rejected because:

1. There is no SSR requirement. CollabSpace's data is user-specific and post-auth; there is no public, indexable content. SSR adds infrastructure complexity (a Node.js server process, not a static file host) without a meaningful benefit.
2. The App Router and Server Components introduce a new mental model — client/server boundary, `'use client'` directives, React Server Components, streaming — that is separate from and orthogonal to the core React skills this project is meant to develop. Learning React fundamentals and the App Router simultaneously is unnecessary cognitive load.
3. Vercel's push-to-deploy experience for Next.js runs a Node.js server process, moving outside the free tier limits faster than a static SPA.

Next.js is the right choice for a content site, a marketing page, or an application where SEO matters. For an authenticated collaboration tool, it is unnecessary.

**Create React App (CRA)**

CRA was the standard React scaffolding tool until its deprecation in February 2025. It is no longer maintained, no longer receives security updates, and is not a viable choice for new projects.

**Redux Toolkit**

Redux is a viable state management solution but imposes significant boilerplate and a learning curve (reducers, selectors, thunks, slices) that is not justified for this application's state complexity. The combination of TanStack Query (server state) + Zustand (client state) covers the same ground with less overhead and is more idiomatic for a React 19 project.

---

## Consequences

**Positive:**
+ The stack is well-documented and widely used; learning resources, examples, and community support are abundant.
+ Each library has a single, well-defined responsibility (routing, server state, client state, forms) — composition over a monolithic framework.
+ shadcn/ui's copy-paste model keeps component implementations visible and modifiable.
+ Static hosting on Vercel is zero-infrastructure from the project's perspective.

**Negative:**
- More libraries to integrate than a framework like Next.js that bundles many of these concerns. The integration surface (Vite + React Router + TanStack Query + Zustand) is more explicit but requires understanding each piece.
- No file-based routing — routes are defined in code. This is less ergonomic than file-based alternatives but more transparent.
- Vercel hosting is outside the AWS ecosystem. The frontend is not managed by Terraform and is not in the same observability pipeline as the backend services.

---

## Revisit when

- SSR becomes a requirement (SEO, public content, performance on first load for unauthenticated users). At that point, Next.js App Router or Remix are the natural migration targets.
- The project adds a mobile client, at which point the auth and state patterns may diverge enough to warrant re-evaluating the web stack in isolation.
