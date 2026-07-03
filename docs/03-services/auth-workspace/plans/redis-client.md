# Plan: redis-client

**Service:** auth-workspace
**Tier:** Small (override — new dependency, but foundational/no-business-logic, same precedent as PR 1)

## 1. Slice statement
auth-workspace can connect to Redis and report its health, with no business logic depending on it yet.

## 2. User-visible behavior
- `GET /actuator/health` includes `components.redis` = `UP` when Redis is reachable
- `GET /actuator/health` shows `components.redis.status=DOWN` when Redis is unreachable
- `GET /actuator/health/liveness` stays `UP` even when Redis is down
- Verified by integration tests (Testcontainers `redis:7`)
- Post-merge AWS smoke test (per feature-workflow.md's DoD) must save a response showing `components.redis.status=UP` specifically — not just a `200` — since this is the only place the real `rediss://` TLS+auth path gets exercised (Testcontainers is plaintext/unauthenticated)

## 3. API contract (config surface, not a new endpoint)
- `/actuator/health` gains a `redis` key under `components` — automatic once `spring-boot-starter-data-redis` + a working `RedisConnectionFactory` are present
- New `/actuator/health/liveness` path via `management.endpoint.health.probes.enabled=true` + `management.endpoint.health.group.liveness.include=db`
- `probes.enabled=true` also auto-creates `/actuator/health/readiness` — left at Spring Boot's default and intentionally unused (ALB only checks `liveness`); this is a deliberate no-op, documented as such
- Terraform's ALB `health_check_path` changes from `/actuator/health` to `/actuator/health/liveness` in this same PR

## 5. Validation rules
- Malformed `redis_url` (bad URI syntax) → app fails to start. **Tested explicitly** — one edge case with a deliberately malformed value (e.g. `not-a-valid-url`) confirming the Spring context actually fails, rather than assuming framework behavior
- `redis_url` unreachable (valid syntax, network/auth failure) → app starts normally; `components.redis.status=DOWN`; `/actuator/health/liveness` unaffected
- `redis_url` unset entirely → Spring falls back to its own default (`localhost:6379`); app logs a WARN at startup (`event=redis_url_not_configured`) detected via `Environment.containsProperty("spring.data.redis.url")` — not by comparing resolved host/port, which would be fragile to Spring's internal precedence rules
- Liveness group's `db`-down behavior is inherited from PR2's existing indicator and intentionally not retested here — config-only regrouping, no new code path

## 8. Observability
- One startup INFO log line: `event=redis_client_initialized host=<host> port=<port>` — host/port only, never the raw URL or credential. Named "initialized," not "configured" or "connected," since Lettuce connects lazily — this line proves the bean was created, not that a connection succeeded
- One startup WARN log line when `spring.data.redis.url` is absent from the `Environment`: `event=redis_url_not_configured` — makes a missing SSM/env wiring visible immediately instead of silently deferred until PR 7
- No custom logging for health-check polls or state transitions
- Correlation ID: not applicable, no request-handling code in this slice
- Audit events: not applicable, no security-significant action in this slice

## 9. Out of scope
- No business logic (nothing consumes Redis yet)
- No token filter (X-Internal-Token filter is PR 7)
- No `jti` / blocklist logic (JWT blocklist itself is PR 7)
- Future thinking, not decided here: fail-open vs. fail-closed for Redis unavailability once PR 7 makes it load-bearing — flagged for PR 7's plan, not resolved in this one
