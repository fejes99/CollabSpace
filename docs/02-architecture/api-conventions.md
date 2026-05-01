# API Conventions

This document defines the cross-cutting contracts that all CollabSpace HTTP APIs must follow. Every service that exposes HTTP endpoints implements these conventions — they are not optional per-service choices. Consistency here is what makes the frontend and integration tests predictable: a developer who has worked with one service should not need to re-learn the error format, pagination model, or versioning scheme for another.

---

## Versioning

All endpoints are prefixed with a version segment: `/v1/...`. The version is in the URL path, not in a header or query parameter.

URL-prefix versioning is chosen because it is explicit and visible. A request to `/v1/documents` is unambiguous — the version is in the URL and cannot be accidentally omitted. Header-based versioning (`Accept: application/vnd.collabspace.v1+json`) is more RESTful in a theoretical sense but is invisible in browser address bars, harder to test with curl, and less consistently supported by API Gateway route matching.

All routes currently active are v1. When a breaking change to an existing endpoint is required, a `/v2/...` route is introduced alongside the `/v1/...` route, and the v1 route is deprecated with a sunset date before removal. Non-breaking additions (new optional fields, new endpoints) do not require a version bump.

A "breaking change" is any change that requires existing clients to update: removing a field from a response, changing a field's type, removing an endpoint, making an optional request field required, or changing the semantics of an existing field in a way that changes client behaviour.

---

## Error format

All error responses use **RFC 9457 Problem Details** (`Content-Type: application/problem+json`). The format is:

```json
{
  "type": "https://errors.collabspace.io/documents/not-found",
  "title": "Document not found",
  "status": 404,
  "detail": "No document with id 'doc:01HZ...' exists in workspace 'ws:01HX...'.",
  "instance": "/v1/workspaces/ws:01HX.../documents/doc:01HZ..."
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | A URI that identifies the error type. Must be stable and documentable. |
| `title` | Yes | A short, human-readable summary of the error type. Must not change between occurrences. |
| `status` | Yes | The HTTP status code as an integer. Redundant with the response status but useful for logging. |
| `detail` | No | A human-readable explanation specific to this occurrence. May include contextual information. |
| `instance` | No | A URI identifying the specific resource or request that caused the error. |

Additional fields may be present for specific error types. Validation errors include an `errors` array:

```json
{
  "type": "https://errors.collabspace.io/validation/invalid-request",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request body contains invalid fields.",
  "instance": "/v1/auth/register",
  "errors": [
    { "field": "email", "message": "Must be a valid email address." },
    { "field": "password", "message": "Must be at least 12 characters." }
  ]
}
```

### Status code mapping

| Scenario | Status |
|---|---|
| Validation failure (input format) | 400 |
| Missing or invalid authentication | 401 |
| Authenticated but not authorized | 403 |
| Resource not found | 404 |
| Resource already exists (duplicate) | 409 |
| Business invariant violated | 422 |
| Upstream or internal failure | 500 |
| Upstream temporarily unavailable | 503 |

`404` is returned when a resource does not exist *or* when a resource exists but the caller does not have access and revealing its existence would be an information leak. See [authorization.md](authorization.md) for the membership visibility rules.

---

## Pagination

All list endpoints that may return more than one page of results use **cursor-based pagination**. Offset-based pagination (`?page=2&size=20`) is not used.

### Why cursor-based

Offset pagination has two failure modes at scale:

1. **Performance.** `OFFSET 1000 LIMIT 20` causes the database to scan and discard 1000 rows before returning 20. As the offset grows, query time grows with it.
2. **Consistency.** If a row is inserted or deleted between two paginated requests, offset pagination can skip or duplicate items. Cursor-based pagination tracks position by a stable value in the data, so inserts and deletes between pages do not affect the result.

### Cursor format

Cursors are **opaque Base64-encoded JSON**. A cursor encodes the values needed to resume the query at the correct position:

```json
// decoded cursor (internal representation — clients must not parse this)
{ "created_at": "2026-04-15T10:32:00Z", "id": "doc:01HZ..." }
```

```
// encoded cursor (what the client receives and sends)
eyJjcmVhdGVkX2F0IjoiMjAyNi0wNC0xNVQxMDozMjowMFoiLCJpZCI6ImRvYzowMUhaLi4uIn0=
```

Cursors are opaque to clients — they must not be parsed, constructed, or modified. The internal structure may change between API versions without notice. The server validates the decoded cursor values before using them in a query, regardless of whether the cursor appears well-formed.

Cursors are not signed (no HMAC). The server validates the resulting database query: even if a client constructs or modifies a cursor, the query will return the correct page for the decoded values or return an error if the values are invalid. HMAC signing is deferred to v1.5 if cursor tampering becomes a concern.

### Request and response shape

```
GET /v1/workspaces/{workspaceId}/documents?limit=20&after=<cursor>
```

```json
{
  "data": [ /* array of items */ ],
  "pagination": {
    "hasNextPage": true,
    "nextCursor": "eyJjcmVhdGVkX2F0Ijo...",
    "limit": 20,
    "count": 20
  }
}
```

| Parameter | Description |
|---|---|
| `limit` | Number of items per page. Default: 20. Maximum: 100. |
| `after` | Cursor from the previous response's `nextCursor`. Omit for the first page. |
| `hasNextPage` | `true` if more items exist after this page. |
| `nextCursor` | Cursor to pass as `after` in the next request. `null` if `hasNextPage` is `false`. |
| `count` | Number of items returned in this response (≤ `limit`). |

Default ordering is `created_at ASC, id ASC`. Endpoints that support alternative orderings document them explicitly; the cursor format may differ per ordering.

---

## CORS

The API allows cross-origin requests from the registered frontend origin only. The configuration is:

```
Access-Control-Allow-Origin: https://app.collabspace.io
Access-Control-Allow-Credentials: true
Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Correlation-ID, X-Idempotency-Key
Access-Control-Max-Age: 86400
```

`Access-Control-Allow-Origin` must be the exact frontend origin — not a wildcard. A wildcard (`*`) is incompatible with `credentials: 'include'` (required for the HTTP-only cookie transport of refresh tokens) and will be rejected by the browser.

`Access-Control-Allow-Credentials: true` is required because the refresh token is transported in an HTTP-only cookie. Without this header, the browser will not send credentials (cookies, Authorization headers) on cross-origin requests, and the frontend will not be able to reach the auth endpoints.

Preflight requests (`OPTIONS`) must return `200 OK` with the CORS headers. API Gateway handles preflight response for routes it manages; the Auth service (which manages cookie-setting responses) must handle `OPTIONS /auth/*` routes explicitly.

In local development, the allowed origin is typically `http://localhost:5173` (Vite's default dev server port). This is configured via environment variable (`CORS_ALLOWED_ORIGIN`) and must not be hardcoded.

---

## Correlation IDs

Every request carries a **correlation ID** that identifies the request across all services it touches. This is the primary tool for tracing a user-visible error back through the service logs.

### Propagation

The frontend generates a UUID v7 correlation ID for each user-initiated action and sends it in the `X-Correlation-ID` request header. API Gateway passes this header through to the upstream service unchanged.

Each service:
1. Reads the `X-Correlation-ID` header on incoming requests.
2. If absent (for internally generated requests or misconfigured callers), generates a new UUID v7.
3. Attaches the correlation ID to the logging context for every log line emitted during that request's processing.
4. Includes the `X-Correlation-ID` header in outgoing responses so the frontend can log it alongside any UI error.
5. Passes the `X-Correlation-ID` header in any downstream HTTP call (including the AI Assistant → Document Service service-to-service call).

The correlation ID is what makes a CloudWatch Logs Insights query across multiple services possible: `filter correlationId = 'xxx'` returns every log line from every service that touched the request.

---

## Idempotency

Idempotency keys for write operations are **deferred to v1.5**. The pattern is documented here so the implementation is consistent when the time comes.

When implemented, clients that need to safely retry a write operation (document creation, workspace creation) send an `X-Idempotency-Key` header with a client-generated UUID. The server stores the key and the associated response. If the same key is received again within the TTL window (typically 24 hours), the stored response is returned without re-executing the operation.

Idempotency is most valuable when client retry logic cannot distinguish between "the server received and processed my request" and "the server never received my request." Until the first client integration that retries on network failure, this concern is deferred. → [roadmap.md](../roadmap.md)

---

## Authentication header

Authenticated endpoints expect the access token in the `Authorization: Bearer <token>` header. The token is a JWT signed with RS256 as described in [authentication.md](authentication.md).

API Gateway validates the token before forwarding the request. Services receive the raw JWT in the `Authorization` header and may parse the claims for authorization decisions (see [authorization.md](authorization.md)) but must not re-validate the signature — that is API Gateway's responsibility.

Exception: the auth endpoints (`/v1/auth/login`, `/v1/auth/register`, `/v1/auth/refresh`) do not require a token. They are unauthenticated routes in the API Gateway configuration.
