# CollabSpace

A learning project: a five-service real-time collaboration platform built on AWS. The goal is to build something non-trivial end-to-end — authentication, document editing, real-time sync, AI assistance, and notifications — while practising production-grade patterns around infrastructure, CI/CD, observability, and architectural decision-making. Shipping speed is not the objective; understanding every decision is.

## Status

**Stage 0 — Foundation, in progress**

## Tech Stack

| Layer | Technology |
|---|---|
| Auth & Workspace | Java 21, Spring Boot 3, PostgreSQL, Redis |
| Document Service | Node.js 22, TypeScript, Express, MongoDB Atlas |
| Realtime Service | Node.js 22, TypeScript, WebSocket (ws), Redis pub/sub |
| AI Assistant | Python 3.13, FastAPI, PostgreSQL + pgvector |
| Notification | AWS Lambda (Node.js 22) |
| Messaging | SNS + SQS (fan-out), Kafka on EC2 (AI events) |
| Infrastructure | AWS, Terraform, ECS Fargate, EC2, GitHub Actions |

For rationale behind these choices see [docs/02-architecture/technology-choices.md](docs/02-architecture/technology-choices.md).

## Docs

```
docs/
  01-overview/        Vision, use-cases, glossary
  02-architecture/    System overview, tech choices, service communication
  03-services/        Per-service deep-dives (added as services are built)
  04-infrastructure/  AWS architecture, cost strategy
  05-cicd/            Pipeline overview, deployment strategy
  06-decisions/       Architecture Decision Records (ADRs)
  07-development/     Local setup, coding standards, testing strategy
  08-operations/      Monitoring, runbooks (added later)
```

Start with [docs/01-overview/vision.md](docs/01-overview/vision.md), then [docs/02-architecture/system-overview.md](docs/02-architecture/system-overview.md). ADRs in `docs/06-decisions/` explain the *why* behind non-obvious choices.

## Running the Project

Local development setup is not yet documented. See [docs/07-development/](docs/07-development/) once those files exist.
