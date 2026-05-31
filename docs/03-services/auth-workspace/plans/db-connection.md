# Plan: auth/db-connection

## Status
- Tier: Small
- State: Draft
- Branch: feat/auth/db-connection

## 1. Slice statement

Wire database connection and include that in health check.

## 2. User-visible behavior

- `GET /actuator/health` returns HTTP 200 with `components.db.status=UP` when the database is
  reachable. Other components (`diskSpace`, `ping`) will also appear in the response — assertions
  must target `$.components.db.status` specifically, not the full body.
- `GET /actuator/health` returns HTTP 503 with `components.db.status=DOWN` when the database is
  unreachable.
- The `components.db` key is always present in the response (verifies `show-components=always`
  config is active).
- Integration tests (Testcontainers PostgreSQL) verify all three behaviors above.

## 3. API contract

**Endpoint:** `GET /actuator/health` (existing — no new endpoints introduced)

| Field          | Value                                                             |
|----------------|-------------------------------------------------------------------|
| Path           | `/actuator/health`                                                |
| Method         | GET                                                               |
| Auth           | None (public route — no JWT, no X-Internal-Token check)           |
| Request body   | None                                                              |
| Response (200) | `{"status":"UP","components":{"db":{"status":"UP"}, ...}}`        |
| Response (503) | `{"status":"DOWN","components":{"db":{"status":"DOWN"}, ...}}`    |

Status codes: `200` when UP, `503` when DOWN or OUT_OF_SERVICE.

**Internal token note:** ALB health check requests do not carry `X-Internal-Token`.
`/actuator/health` must be explicitly excluded from the internal token filter — otherwise the ALB
marks the task unhealthy and ECS kills it. This exclusion must be wired before first deployment.

## 4. Validation rules (startup-time)

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` must be set
  and non-blank — Spring Boot auto-configuration fails the context at startup if any are missing.
- DB connection must succeed within 10 seconds of startup —
  `spring.datasource.hikari.initialization-fail-timeout=10000`; service refuses to start if Neon
  is unreachable at boot.

**Known gotchas (not validated in code — developer must get these right in config):**
- The JDBC URL scheme must be `jdbc:postgresql://`. Neon's web UI shows `postgres://` —
  these are not interchangeable and `postgres://` will fail at startup with a cryptic driver error.
- `sslmode=require` must be present in the URL. Omitting it causes a TLS negotiation failure
  against Neon that surfaces as a connection error, not a clear config message.

## 5. Observability

**Log lines — custom vs. native**

| Event                       | Level | Source   | Fields                                            |
|-----------------------------|-------|----------|---------------------------------------------------|
| Pool initialized at startup | INFO  | HikariCP | Emitted automatically — no custom code needed     |
| Pool failed at startup      | ERROR | HikariCP | Emitted automatically — no custom code needed     |
| Health transitions DOWN     | WARN  | Custom   | `event=db.health.down`, `previousStatus=UP`       |
| Health recovers UP          | INFO  | Custom   | `event=db.health.recovered`, `previousStatus=DOWN`|

HikariCP redacts passwords from its own log lines automatically. The custom health transition log
lines must log `host` only — never the full JDBC URL — because Neon connection strings may embed
credentials. Extract the host from `DataSourceProperties` at bean construction time (before any
runtime URL parsing is needed), not by string-splitting the URL on every log call.

**Initial state:** before the first health check poll completes, the stateful health indicator has
no previous status. Treat `null → DOWN` as a transition and emit `event=db.health.down` with
`previousStatus=UNKNOWN`. Do not emit a log line for `null → UP` (normal startup path).

**Timeout vs. refused:** both surface as DOWN. The `error` field content will differ
(`Connection timed out` vs. `Connection refused`) — this is expected and does not require special
handling. The log line shape is the same.

**Correlation ID:** ALB health check requests arrive without `X-Correlation-ID`.
`CorrelationIdFilter` generates one automatically — no extra work needed. The generated IDs appear
in logs but have nothing to correlate to; this is expected noise.

**Audit events:** None — no user action involved.

**Custom code note:** the stateful health indicator (tracking previous status, logging transitions)
is a custom Spring component — it is not auto-wired by Spring Boot. Plan for it explicitly during
implementation.

## 6. Integration test setup

Testcontainers must be fully started before the Spring context initializes — because
`initialization-fail-timeout=10000` means a 10-second wait before context failure if the DB URL
is not yet available. Use `@ServiceConnection` (Spring Boot 3.1+) or `DynamicPropertySource` to
wire the container's JDBC URL into Spring before context startup.

**Simulating DB unreachable:** stop the Testcontainers container mid-test (or before context
startup for the failure case). Do not point at an invalid host — Testcontainers container stop
is the reliable, repeatable mechanism.

## 7. Local development

`.env` variables required to run locally:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>/<db>?sslmode=require
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<password>
```

- Use `jdbc:postgresql://` — not `postgres://` (Neon web UI format).
- `sslmode=require` is mandatory — Neon rejects connections without SSL.
- `show-components=always` applies in all environments including local — this is intentional.

## 8. Out of scope

- No Flyway migrations
- No new DB tables
- No new endpoints
