# Kafka Consumer Retry Policy

This document describes how the AI Assistant handles failures when consuming events from the `ai-events` Kafka topic. It covers the retry schedule, dead-letter topic design, alerting, and the manual replay procedure.

This is an instance of **event-driven eventual consistency**: when a document is created or updated, it will be indexed in the AI service's vector store eventually — not necessarily at the moment the event is published. The retry policy defines the bounds on "eventually."

---

## The failure scenario

The AI Assistant consumes `document.indexed` events from `ai-events`. On each event, it calls `GET /documents/:id` on the Document Service to fetch content, then writes to pgvector.

If the Document Service is unavailable (ECS task replacement, health check failure, network blip), the HTTP call fails. The consumer must not drop the event, but it also must not hammer the Document Service in a tight loop.

---

## Retry policy

**Algorithm:** Exponential backoff with ±20% jitter
**Max retries:** 5
**After max retries:** Route to dead-letter topic `ai-events.DLT`

| Attempt               | Base delay | Effective range |
| --------------------- | ---------- | --------------- |
| 1                     | 1s         | 0.8–1.2s        |
| 2                     | 2s         | 1.6–2.4s        |
| 3                     | 4s         | 3.2–4.8s        |
| 4                     | 8s         | 6.4–9.6s        |
| 5                     | 16s        | 12.8–19.2s      |
| → DLT after attempt 5 |            |                 |

Total window before DLT routing: approximately 32–37 seconds.

Jitter prevents retry storms: when multiple consumers fail on the same outage simultaneously, random jitter spreads their retries across the window rather than spiking load at exactly the same moment.

---

## Retry state: message headers

Kafka does not manage retry counts natively. The consumer tracks retry state using **Kafka message headers**:

| Header                | Value                                    |
| --------------------- | ---------------------------------------- |
| `retry-count`         | Number of previous attempts (absent = 0) |
| `first-failure-ts`    | ISO timestamp of the first failure       |
| `last-failure-reason` | Short error description                  |

On each retry, the consumer increments `retry-count` in the header and re-seeks the offset (does not commit). On DLT routing, the message — including all headers — is produced to `ai-events.DLT` and the original offset is committed.

This provides a built-in audit trail: the DLT message tells you how many times the event failed and why.

---

## Dead-letter topic

**Topic:** `ai-events.DLT`
**Partition count:** Same as `ai-events` (preserves message order within a document's partition)
**Retention:** 7 days (same as `ai-events`)

The DLT is a standard Kafka topic. It contains the original event payload plus the retry headers. It is not processed automatically — messages sit there until an operator runs the replay procedure.

---

## Alerting

A CloudWatch alarm monitors the `ai-events.DLT` consumer group lag:

```
Alarm: ai-events-dlt-consumer-lag
Threshold: > 0 messages
Period: 1 minute
Action: SNS → email notification
```

Any message in the DLT means at least one indexing event has failed permanently after 5 retries. This requires investigation and either replay (if the failure was transient) or explicit acknowledgment (if the document no longer exists).

---

## Operational runbook

### Alert fires: ai-events-dlt-consumer-lag > 0

**1. Identify the failing event**

Read messages from `ai-events.DLT`:

```bash
# From a host with Kafka client access (EC2 Kafka node or jump host)
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic ai-events.DLT \
  --from-beginning \
  --max-messages 10
```

Check the message headers for `retry-count` and `last-failure-reason`. Note the document ID in the payload.

**2. Identify the root cause**

Check CloudWatch logs for the AI Assistant service (`/collabspace/dev/ai-assistant`) around the `first-failure-ts` timestamp. Look for:

- `5xx` responses from Document Service → Document Service was down or returning errors
- `404` responses from Document Service → the document was deleted before indexing
- Network timeout → transient connectivity issue

**3a. If Document Service was temporarily unavailable and is now healthy**

Run the replay script to re-publish DLT messages to the original topic:

```bash
# Replay all DLT messages
python scripts/replay_dlt.py --topic ai-events.DLT --target ai-events

# Replay a specific document ID
python scripts/replay_dlt.py --topic ai-events.DLT --target ai-events \
  --filter-document-id <id>
```

The replay script reads from `ai-events.DLT`, strips the `retry-count` and `last-failure-*` headers, and produces to `ai-events`. The AI consumer processes them as new events. After replay, confirm the document is searchable in the AI service.

**3b. If the document was deleted (404)**

The event cannot be processed. The document does not exist. No action required for indexing — a deleted document should not be in the vector store. Mark the DLT message as acknowledged (commit the DLT consumer offset) and close the alert.

If the document appears to exist but Document Service returns 404, investigate Document Service data integrity separately.

**3c. If the failure reason is unknown**

Do not replay blindly. Investigate the Document Service and AI Assistant logs fully before replaying — replay into a broken system just re-generates DLT messages.

**4. Verify**

After replay:

- Check `ai-events.DLT` consumer lag drops to 0.
- Verify the affected document(s) are returned in AI search results.
- Close the alert.

---

## What eventual consistency means for users

When a user creates or edits a document:

1. Document Service saves the document (immediate, synchronous).
2. Document Service publishes to Kafka (immediate).
3. AI Assistant consumes the event and indexes the document (asynchronous, best-case within seconds).

If Document Service is temporarily down when the AI Assistant processes the event:

- The document is saved correctly (the Document Service is not down for the user's write — only for the AI's read).
- The AI search results will not include the document until either: the retry succeeds (within ~37 seconds) or the DLT is replayed after Document Service recovers.
- The user may not find their document in AI search immediately after creation. This is expected behaviour — the UI should not imply that AI search is immediately consistent.

This is the "eventual" in eventual consistency. The bound is: within 37 seconds under normal conditions, or within the outage duration + replay time under failure conditions.

---

## See also

- [ADR-019: Kafka Consumer Retry Policy and Dead-Letter Topic](../06-decisions/adr-019-kafka-consumer-retry-policy.md) — decision rationale and alternatives considered
- [ADR-003: Broker Strategy](../06-decisions/adr-003-broker-strategy.md) — why Kafka is used for AI events
