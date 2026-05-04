# ADR-019: Kafka Consumer Retry Policy and Dead-Letter Topic

**Status:** Accepted
**Date:** 2026-05-04

---

## Context

The AI Assistant service consumes events from the `ai-events` Kafka topic. The primary event is `document.indexed` (published by the Document Service when a document is created or updated). On receipt, the AI Assistant calls `GET /documents/:id` on the Document Service to fetch the document content, then indexes it into pgvector.

This creates a runtime dependency: if the Document Service is unavailable when the AI Assistant processes an event, the fetch fails. The Kafka consumer must decide what to do with the failed event.

The naive approaches are both wrong:

- **Drop the event:** the document is never indexed. Silent data loss.
- **Retry immediately in a tight loop:** if Document Service is down, the consumer hammers it at full speed, generating error logs and network load that makes recovery harder.

A retry policy defines how many times to retry, how long to wait between retries, and what happens after all retries are exhausted. Documenting this before implementation forces explicit reasoning about failure modes.

This pattern — consuming an event, calling a downstream service, retrying on failure — is called **event-driven eventual consistency**. The document will be indexed eventually; it is not guaranteed to be indexed at the moment of creation.

---

## Decision

Use **exponential backoff with jitter** for retry delays. After **5 consecutive failures**, publish the original event to a **dead-letter topic** (`ai-events.DLT`). Alert when the DLT contains any messages. Provide a documented manual replay procedure.

### Retry schedule

| Attempt | Base delay | Jitter (±20%) | Effective range |
| ------- | ---------- | ------------- | --------------- |
| 1       | 1s         | 0.8–1.2s      | 0.8–1.2s        |
| 2       | 2s         | 1.6–2.4s      | 1.6–2.4s        |
| 3       | 4s         | 3.2–4.8s      | 3.2–4.8s        |
| 4       | 8s         | 6.4–9.6s      | 6.4–9.6s        |
| 5       | 16s        | 12.8–19.2s    | 12.8–19.2s      |
| → DLT   | —          | —             | after attempt 5 |

Total retry window before DLT: approximately 32–37 seconds per event.

Jitter is added to prevent retry storms: if many consumers encounter the same outage simultaneously and retry in lockstep, they create synchronized load spikes on recovery. Jitter spreads the retries across a time window.

---

## Rationale

### Why exponential backoff

A fixed-delay retry (e.g., retry every 5 seconds, forever) has two problems:

1. If the downstream service recovers in 1 second, you wait unnecessarily.
2. If it is down for 10 minutes, you generate 120 retry attempts that all fail, polluting logs and metrics.

Exponential backoff starts short (fast recovery detection) and grows long (minimal waste during extended outages). The maximum delay of ~16 seconds means the consumer does not hold up the Kafka partition for extended periods while still backing off enough to avoid hammering a recovering service.

### Why 5 retries before DLT

Five retries gives Document Service approximately 32 seconds to recover from a transient failure (restart, health check recovery). Transient failures — a task being replaced by ECS, a network blip, a brief GC pause — typically resolve in under 30 seconds. Permanent failures (misconfiguration, broken dependency) will not resolve regardless of retries and should go to the DLT faster.

More retries would delay DLT routing for permanent failures. Fewer retries would route transient failures to the DLT unnecessarily, creating operational noise.

### Why a dead-letter topic instead of just logging

A dead-letter topic (`ai-events.DLT`) preserves the original event durably. This enables:

1. **Replay:** once Document Service recovers, an operator can re-publish events from `ai-events.DLT` to `ai-events`. The AI Assistant processes them as if they were new.
2. **Investigation:** the event payload is preserved for debugging. A log line is ephemeral; a Kafka message is queryable.
3. **Alerting:** CloudWatch can alarm on DLT consumer lag > 0, which is unambiguous: any message in the DLT means something failed permanently.

A DLT is the standard Kafka pattern for this scenario. It is more operational than simply discarding failed events and more scalable than storing failed events in a database.

### Why not a circuit breaker

A circuit breaker would stop sending requests to Document Service entirely after N failures, rather than retrying per-event. This is a valid pattern for high-throughput services.

Rejected because:

- The AI indexing workload is low-frequency (one event per document change). The per-event retry schedule is sufficient; the consumer is not under enough load to need circuit-breaker-level protection.
- A circuit breaker adds state to the consumer that must be persisted across restarts. At this scale, that complexity is not justified.
- If the Document Service is down for longer than the retry window, events go to the DLT and the circuit breaker would have the same outcome anyway.

Circuit breakers should be reconsidered if the AI service moves to a bulk re-indexing pattern that generates high request volume.

---

## Rejected alternatives

**Retry indefinitely with a fixed delay**

Retrying forever ensures no event is dropped. Rejected because:

- Blocked partitions: Kafka consumers must commit offsets to advance. If an event is retried indefinitely in-handler, the partition does not advance and subsequent events are not processed. All indexing for documents in the same partition stalls.
- Unbounded log noise during extended outages.
- No escalation path — an operator cannot distinguish "currently retrying" from "permanently stuck".

**Discard failed events after logging**

Simple but creates silent data gaps in the vector index. The AI service would return incomplete search results for documents that were never indexed. This is unacceptable for a feature where completeness of search results is a correctness requirement, not just a quality-of-service concern.

**Re-queue to the original topic**

Publishing the failed event back to `ai-events` (rather than `ai-events.DLT`) would cause the consumer to process it again immediately. This is equivalent to an immediate retry and loses backoff semantics. It also pollutes the main topic with retry events, making audit trails and metrics harder to interpret.

---

## Implementation notes

The AI Assistant is Python + FastAPI. The Kafka consumer uses `confluent-kafka-python` (or `aiokafka` for async).

The consumer implementation must:

1. Track the retry count per message. The Kafka consumer does not do this natively — store it in the message headers (Kafka message headers support arbitrary key-value pairs). Add a `retry-count` header, increment it on each retry.
2. Sleep for the backoff duration before re-seeking to the failed offset. Do not use `consumer.seek()` in a tight loop.
3. After retry 5, produce the message (with its headers) to `ai-events.DLT` and commit the original offset. This advances the partition.

The DLT topic must be created with the same partition count as the source topic. Replay is performed by a one-off script that reads from `ai-events.DLT` and produces to `ai-events`.

---

## Operational runbook

**Alert:** CloudWatch alarm fires for "ai-events.DLT consumer lag > 0"

1. Identify the failing event: read from `ai-events.DLT`. Inspect the `retry-count` header and the event payload.
2. Identify the cause: check CloudWatch logs for the AI Assistant service at the time of the failure. Look for 5xx responses from Document Service.
3. If Document Service was temporarily down and is now healthy: run the replay script to re-publish DLT events to `ai-events`. The consumer will process them in order.
4. If Document Service returned a permanent error (document deleted, malformed response): the event cannot be processed. Log the document ID as an indexing gap. Optionally delete the event from DLT after investigation.
5. Verify: confirm the document appears in pgvector after replay.

---

## Consequences

**Positive:**

- No silent data loss — every failed event is preserved in the DLT.
- Jitter prevents retry storms during common-mode failures.
- DLT enables replay without reprocessing successfully indexed documents.
- CloudWatch alarm gives unambiguous signal that human intervention is needed.
- Retry count in message headers provides a built-in audit trail.

**Negative:**

- Retry delay adds up to ~37 seconds of latency before DLT routing. During that window, the document is not searchable in the AI service. This is acceptable for the "eventual" in eventual consistency.
- The consumer implementation must manage retry state (the retry-count header pattern adds complexity over simple offset commit/rollback).
- Replay is a manual operation. An automated replay on DLT messages would be more ergonomic but adds a component (a DLT consumer with its own retry logic) that is out of scope for v1.

---

## Revisit when

- AI indexing volume increases significantly (bulk import, workspace migration). At that point, evaluate a circuit breaker and batch retry strategy.
- The retry window (37 seconds) causes observable product issues — documents appear unsearchable for too long after creation. Shorten the retry schedule or add proactive re-triggering from the Document Service.
- Automated DLT replay is needed. At that point, write a DLT consumer service as a new component rather than extending the AI Assistant.
