# CollabSpace

A learning project: a five-service real-time collaboration platform built on AWS. The goal is to build something non-trivial end-to-end — authentication, document editing, real-time sync, AI assistance, and notifications — while practising production-grade patterns around infrastructure, CI/CD, observability, and architectural decision-making. Shipping speed is not the objective; understanding every decision is.

## Status

**Stage 1 — Walking Skeleton, in progress**

Infrastructure live in AWS dev (`eu-central-1`): VPC, ECS cluster, ALB, security groups, IAM roles, CloudWatch log groups. Next: push `auth-workspace` Spring Boot container to ECR and verify it's reachable via the ALB DNS name.

## Tech Stack

| Layer            | Technology                                            |
| ---------------- | ----------------------------------------------------- |
| Auth & Workspace | Java 25, Spring Boot 4, PostgreSQL, Redis             |
| Document Service | Node.js 22, TypeScript, Express, MongoDB Atlas        |
| Realtime Service | Node.js 22, TypeScript, WebSocket (ws), Redis pub/sub |
| AI Assistant     | Python 3.13, FastAPI, PostgreSQL + pgvector           |
| Notification     | AWS Lambda (Node.js 22)                               |
| Messaging        | SNS + SQS (fan-out), Kafka on EC2 (AI events)         |
| Infrastructure   | AWS, Terraform, ECS Fargate, EC2, GitHub Actions      |

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

Start with [docs/01-overview/vision.md](docs/01-overview/vision.md), then [docs/02-architecture/system-overview.md](docs/02-architecture/system-overview.md). ADRs in `docs/06-decisions/` explain the _why_ behind non-obvious choices.

## Running the Project

The dev environment runs on AWS, not locally. To bring it up:

```bash
cd infrastructure/environments/dev
terraform init && terraform plan && terraform apply
```

See [infrastructure/README.md](infrastructure/README.md) for the full layer-by-layer bring-up sequence (bootstrap → shared → dev). Local service development setup will be documented in [docs/07-development/](docs/07-development/) once services are scaffolded.
