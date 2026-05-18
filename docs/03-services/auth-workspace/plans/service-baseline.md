# Plan: service-baseline

**Service:** auth-workspace
**Branch:** feat/auth/service-baseline
**Tier:** Full — two new dependencies (logstash-logback-encoder, springdoc-openapi); kept as Full
**Status:** [ ] Not started

---

## 1. Slice statement

The auth service has logging, error handling, OpenAPI, and correlation IDs with all dependencies.

---

## 2. User-visible behavior

- Any request to the service produces a structured JSON log line containing at minimum: `timestamp`, `level`, `message`, `correlationId`
- Service startup emits at least one structured JSON log line (verifiable by checking the first console line after startup)
- An unhandled exception returns a response body matching RFC 9457 Problem Details: `type`, `title`, `status`, `detail`, `instance` with the correct HTTP status code
- A request with `X-Correlation-ID: abc123` in the header produces log lines where every line contains `correlationId: abc123`, and the response includes `X-Correlation-ID: abc123`
- `GET /v3/api-docs` returns `200` with a valid OpenAPI 3.x JSON document
- `GET /swagger-ui.html` returns the interactive Swagger UI listing all documented endpoints

---

## 3. API contract

### GET /v3/api-docs
- **Auth:** None
- **Request body:** None
- **Response (200):** Valid OpenAPI 3.x JSON document
- **Exposure:** Internal tooling only — not routed through API Gateway in any environment

### GET /swagger-ui.html
- **Auth:** None
- **Request body:** None
- **Response (200):** Interactive Swagger UI HTML page
- **Exposure:** Internal tooling only — not routed through API Gateway in any environment

### Cross-cutting: X-Correlation-ID
- Every response includes `X-Correlation-ID` header echoing the incoming value (or a generated UUID v4 if absent or empty)
- The correlation ID filter also sets `Access-Control-Expose-Headers: X-Correlation-ID` so browser JavaScript can read the header on cross-origin responses

### Cross-cutting: error responses
- All non-2xx responses use `Content-Type: application/problem+json`
- Known errors: `type` is `https://errors.collabspace.io/auth/<slug>`
- Unhandled exceptions (500): `type` is `about:blank`, `title` is `Internal Server Error`, `detail` is `An unexpected error occurred.`

### Dependencies introduced
- `net.logstash.logback:logstash-logback-encoder` — structured JSON log output via Logback
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` — OpenAPI spec generation and Swagger UI

---

## 4. Data model changes

None.

---

## 5. Validation rules

| Input | Constraint | Behavior |
|---|---|---|
| `X-Correlation-ID` request header | Non-empty string | Absent or empty → generate UUID v4; present and non-empty → use as-is |
| `X-Correlation-ID` value length | Max 64 characters | Truncate silently to 64 chars — oversized values are a caller bug, not worth a 400 |

---

## 6. Edge cases

| Scenario | Expected behavior |
|---|---|
| Request with no `X-Correlation-ID` header | Service generates UUID v4; used in all log lines and echoed in response header |
| Request with empty `X-Correlation-ID` value | Treated as absent — generate UUID v4 |
| `X-Correlation-ID` value longer than 64 chars | Truncated silently to 64 chars; truncated value used in logs and response header |
| Controller throws an unmapped exception | `500` + RFC 9457: `type: about:blank`, `title: Internal Server Error`, `detail: An unexpected error occurred.` |
| Controller throws a mapped exception | Correct 4xx status + RFC 9457: `type: https://errors.collabspace.io/auth/<slug>` |

---

## 7. Authorization

No auth required for any endpoint in this PR. `/v3/api-docs` and `/swagger-ui.html` are unauthenticated by design. The correlation ID filter and exception handler are cross-cutting infrastructure — they run on all requests regardless of auth state.

If an API Gateway route is ever added for `/v3/api-docs` or `/swagger-ui.html`, it must be blocked at the Gateway level — do not rely on "no route exists" as the only protection.

---

## 8. Observability

### Log levels
- `DEBUG` — per-request access lines (method, path, status, duration)
- `INFO` — application events (service started, configuration loaded)
- `ERROR` — unhandled exceptions with `correlationId` and exception message

### Log line fields (minimum per request)
`timestamp`, `level`, `message`, `correlationId`, `service`

### Correlation ID lifecycle
Stored in MDC at the start of each request; cleared at the end. Every log line emitted during the request inherits it automatically via MDC.

### Integration test assertions
The following three behaviors must each have an explicit test:
1. A request produces a JSON log line containing `correlationId`
2. An unhandled exception produces a `500` response with RFC 9457 body (`type`, `title`, `status`, `detail`)
3. A request with `X-Correlation-ID` header receives the same value echoed in the response header

No audit events — no user-identity-touching behavior in this PR.

---

## 9. Out of scope

- No database connection
- No new business endpoints
- No Redis connection
