# ADR-020: WebSocket Authentication Flow

**Status:** Accepted
**Date:** 2026-05-04

---

## Context

The Realtime Service maintains persistent WebSocket connections for real-time document collaboration. Every connection must be associated with an authenticated user — unauthenticated WebSocket connections must be rejected.

WebSocket connections start as HTTP requests (the upgrade handshake). After the server accepts the upgrade, the connection becomes a persistent bidirectional channel with no further HTTP semantics. This creates two distinct auth problems:

1. **Authentication at connection time:** How does the server establish that the connecting client is who they claim to be?
2. **Token expiry during an active connection:** Access tokens are short-lived (15 minutes per the auth model). What happens when a token expires while a connection is open?

The Realtime Service is deployed on EC2 (not Fargate) to maintain persistent WebSocket connections without the connection cost of task replacement. The WebSocket traffic reaches it via ALB WebSocket listener, not through API Gateway — API Gateway's JWT authorizer does not apply here. WebSocket auth is entirely the Realtime Service's responsibility.

This intersects with ADR-015 (RS256 signing) and the authentication architecture in `docs/02-architecture/authentication.md`.

---

## Decision

**At connection time:** Validate the JWT carried in the `Authorization: Bearer <token>` header of the HTTP upgrade request. If valid, extract `userId` and the user's workspace memberships. Store these in a per-connection registry backed by Redis (to support multi-instance Realtime Service nodes sharing connection state). If invalid, reject the upgrade with HTTP 401.

**On token expiry:** The server closes the connection with WebSocket close code `4001` when the stored token expiry is reached. The client is expected to refresh the access token (via `POST /v1/auth/refresh` on the Auth service) and reconnect. The client's `onclose` handler is the trigger.

**Token transport:** `Authorization: Bearer <token>` header on the upgrade request. The token is not passed in the URL query string.

---

## Rationale

### Authentication at connection time

The HTTP upgrade request is a standard HTTP request and supports all standard HTTP headers. The `Authorization: Bearer <token>` header is the natural carrier — it is the same mechanism used for REST API calls, the client already has the token in memory, and no additional transport mechanism is needed.

ALB preserves custom headers through WebSocket upgrades and forwards them to the backend. The Realtime Service reads the header in the upgrade handler before accepting the WebSocket connection.

The Realtime Service validates the JWT using the RS256 public key (fetched from Auth service's JWKS endpoint or cached from SSM). Validation checks: signature correctness, expiry, issuer, audience. If any check fails, the server sends HTTP 401 and the WebSocket upgrade is refused — the client never receives a WebSocket connection.

On successful validation, the connection is registered:

```
Redis HSET ws:conn:<connectionId> userId <userId> workspaces <json> expiresAt <epoch>
```

### Why Redis for connection registry

The Realtime Service runs on EC2. For resilience and horizontal scaling, multiple EC2 instances may handle connections simultaneously. Redis (the same Upstash instance used for the auth blocklist) provides a shared connection registry across all instances.

This enables:

- Instance A can look up which users are connected via Instance B (needed for workspace-scoped broadcast).
- If Instance A crashes, the registry records its stale connections; Instance B can detect stale entries via TTL and skip them.

Without Redis, each instance would only know about its own connections. Cross-instance broadcasting would not be possible.

### Token expiry: why close and reconnect (Option A)

Three options were considered:

**Option A (chosen): Server closes connection on token expiry.**
The server reads `expiresAt` from the connection registry (set at connect time). It runs a lightweight background sweep (every 30 seconds) checking for expired connections and sends WebSocket close code `4001` to each. The client's `onclose` handler detects `4001`, refreshes the access token, and reconnects.

**Option B: Re-authentication message.**
The client sends a `{ type: "auth", token: "..." }` message before the token expires. The server validates the new token and updates the registry entry.

**Option C: Client-side proactive reconnect.**
The client closes and reopens the connection before the token expires, without server involvement.

Option A is chosen because:

- It is the simplest server implementation. No protocol message type for re-auth, no client-side timer management.
- It is consistent with stateless design — the server never trusts a connection indefinitely; tokens are always re-validated at reconnect.
- It teaches reconnection resilience, which is needed anyway for network drops and server restarts.
- The UX impact is a brief (~100ms) reconnection that is invisible to the user for chat and cursor updates.

Option B is rejected because it adds protocol complexity (a new message type that is not a business event) and requires the client to manage a refresh-before-expiry timer, which is already managed separately for REST calls. Managing two expiry timers for the same token in different contexts is error-prone.

Option C is rejected because it requires the client to know about token expiry independently for the WebSocket context, duplicating logic already in the auth hooks.

### Token transport: header, not query string

Tokens carried in URL query strings are logged by ALB access logs, CloudWatch, and any intermediary. A token in a log is a leaked credential. The `Authorization` header is not logged by default.

The browser's native `WebSocket` API does not support custom headers (only HTTP upgrades initiated via `XMLHttpRequest` or `fetch` can set custom headers natively). However, the CollabSpace frontend uses the native `WebSocket` API.

Mitigation for the browser limitation: the client fetches a short-lived, single-use WebSocket ticket from the Auth service (`POST /v1/auth/ws-ticket`). The ticket is a random 128-bit value stored in Redis with a 30-second TTL. The client passes the ticket as a URL parameter (`?ticket=<value>`). The Realtime Service exchanges the ticket for the associated `userId` (Redis GET → delete) at connection time.

This is a standard pattern for WebSocket authentication in browser contexts. The ticket is:

- Short-lived (30 seconds): even if logged, it expires before it can be exploited.
- Single-use: the Redis DELETE ensures the ticket cannot be replayed.
- Not a JWT: it has no intrinsic meaning; only the Redis lookup makes it valid.

This requires a `POST /v1/auth/ws-ticket` endpoint on the Auth service (returns `{ ticket: "<value>" }`) and a Redis key `ws:ticket:<value> → <userId>` with 30-second TTL.

---

## Rejected alternatives

**Pass JWT as query parameter**

Simple, works with all browsers. Rejected because JWTs in URL query strings are logged by every layer of infrastructure between the client and the server. A 15-minute access token in a CloudWatch log is a 15-minute credential exposure window. This is an unacceptable trade-off.

**Validate JWT on every WebSocket message**

Re-validating the JWT on every message guarantees the connection is always operating with a valid token. Rejected because:

- WebSocket messages do not have a standard place to carry a token (no equivalent of `Authorization` header per message).
- Embedding the JWT in every message payload adds significant per-message overhead.
- It duplicates the API Gateway JWT validation pattern in a context where it doesn't apply cleanly.

**Skip auth on WebSocket, trust API Gateway upstream**

API Gateway's WebSocket API supports a `$connect` route with a Lambda authorizer that runs once at connection time. However, this project routes WebSocket traffic through ALB directly (not API Gateway WebSocket API) because the Realtime Service is on EC2, not a Lambda. API Gateway WebSocket API requires Lambda or HTTP integration backends that can handle the connection lifecycle events. The EC2-based Realtime Service does not fit this model.

---

## Connection lifecycle

```
Client                          ALB                      Realtime Service
  |                              |                              |
  |-- GET /ws?ticket=<value> --->|                              |
  |   Upgrade: websocket         |-- GET /ws?ticket=<value> -->|
  |                              |                              |-- Redis GET ws:ticket:<value>
  |                              |                              |   → userId (DEL on hit)
  |                              |                              |-- Validate: found? not expired?
  |                              |                              |-- Redis HSET ws:conn:<connId>
  |<--- 101 Switching Protocols -|<-- 101 Switching Protocols --|
  |                              |                              |
  [... WebSocket messages ...]   |                              |
  |                              |                              |
  |                              |      [token expiry check]   |
  |<--- Close 4001 (token exp) --|<-- Close 4001 --------------|
  |                              |                              |
  |-- [refresh token] --------> Auth Service                   |
  |<-- [new access token] ------ Auth Service                  |
  |                              |                              |
  |-- GET /ws?ticket=<new> ----->|                              |
  [reconnect...]                 |                              |
```

---

## Consequences

**Positive:**

- No credentials in URL logs (ticket pattern).
- Connection state in Redis enables multi-instance broadcast.
- Server-driven close on expiry ensures no connection operates with a stale identity.
- Reconnection logic teaches a pattern needed for network resilience regardless.

**Negative:**

- The WebSocket ticket endpoint (`POST /v1/auth/ws-ticket`) is an additional Auth service endpoint not present in standard REST auth flows. It must be implemented alongside the Auth service.
- The 30-second ticket TTL creates a race: if the client takes more than 30 seconds to open the WebSocket after fetching the ticket, the connection will be rejected. In practice this does not happen; the ticket fetch and upgrade are sequential operations that complete in milliseconds.
- The background expiry sweep on the Realtime Service adds a periodic task that must be well-tested — a bug that closes all connections every sweep would be severe.

---

## Revisit when

- The Realtime Service moves to a Lambda-based architecture. At that point, the API Gateway WebSocket API with a Lambda authorizer on `$connect` becomes viable and eliminates the ticket mechanism.
- Token lifetimes are shortened (e.g., to 5 minutes for security reasons). The reconnection frequency increases; evaluate whether Option B (re-auth messages) becomes less disruptive than Option A at higher reconnect rates.
