# Local Development Setup

This document describes how to run the CollabSpace stack locally. The model is: **infrastructure in Docker, services run natively**. Docker Compose brings up the databases, message brokers, and AWS emulation. Each service is started natively — `./mvnw spring-boot:run`, `npm run dev`, `uvicorn` — so you get hot reload, native debugger support, and no Docker rebuild cycle on code changes.

Services are also available as Docker Compose profiles for when you need to run the full stack in containers (Dockerfile verification, integration tests, CI-equivalent environment). The default workflow does not require them.

---

## Prerequisites

Install these before running anything:

| Tool | Purpose | Install |
|---|---|---|
| Docker Desktop (or Rancher Desktop) | Container runtime for infrastructure | [docker.com](https://docker.com) |
| Java 25 (JDK) | Auth & Workspace service | `sdk install java 25-tem` via SDKMAN |
| Node.js 22 | Document, Realtime, Notification services and frontend | `nvm install 22` |
| Python 3.13 | AI Assistant service | `pyenv install 3.13` |
| AWS CLI v2 | LocalStack interaction, ECR login | [aws.amazon.com/cli](https://aws.amazon.com/cli) |
| Terraform 1.9+ | Optional — only needed to run `terraform plan/apply` locally | [terraform.io](https://terraform.io) |

SDKMAN (`sdk`) manages Java versions. nvm manages Node.js versions. pyenv manages Python versions. Using these version managers avoids system-level Java/Node/Python conflicts between projects.

---

## Infrastructure stack (Docker Compose)

The `docker-compose.yml` at the repository root defines the infrastructure services. Start it with:

```bash
make up
```

This brings up:

| Service | Port | Purpose |
|---|---|---|
| PostgreSQL 16 | 5432 | Auth & Workspace (auth_db) + AI Assistant (vector_db) |
| MongoDB 7 | 27017 | Document Service |
| Redis 7 | 6379 | Auth (JWT blocklist, refresh token storage) + Realtime (pub/sub coordination) |
| LocalStack | 4566 | AWS emulation: SNS, SQS, Lambda |

Stop and remove containers:

```bash
make down
```

Data is persisted in named Docker volumes (`pg_data`, `mongo_data`, `redis_data`) so it survives `make down` / `make up` cycles. To reset all data to a clean state:

```bash
make reset
```

`make reset` runs `docker compose down -v` (removes volumes) followed by `make up` and all migration commands. Use this when you need a clean database state for testing or when a migration cannot be applied incrementally.

---

## Running services natively

Each service is started independently from its own directory. Open a terminal tab per service.

### Auth & Workspace (Java 25 / Spring Boot 4)

```bash
cd services/auth-workspace
./mvnw spring-boot:run
```

Runs on port `8080`. Hot reload is not enabled for Spring Boot by default — add `spring-boot-devtools` to the Maven dependencies for class reloading on save. The service reads its configuration from environment variables (see [Environment variables](#environment-variables)).

Health check: `curl http://localhost:8080/actuator/health`

### Document Service (TypeScript / Express)

```bash
cd services/document-service
npm install        # first time only
npm run dev
```

Runs on port `3001`. `npm run dev` uses `tsx --watch` (or the equivalent) for TypeScript hot reload on file save.

Health check: `curl http://localhost:3001/health`

### Realtime Service (TypeScript / ws)

```bash
cd services/realtime-service
npm install        # first time only
npm run dev
```

Runs on port `3002` (HTTP health check) and opens the WebSocket server on port `3003`. In local dev, the WebSocket server does not sit behind an ALB — the frontend connects directly to `ws://localhost:3003`.

Health check: `curl http://localhost:3002/health`

### AI Assistant (Python 3.13 / FastAPI)

```bash
cd services/ai-assistant
python -m venv .venv        # first time only
source .venv/bin/activate   # macOS/Linux
pip install -r requirements.txt  # first time only
uvicorn app.main:app --reload --port 8001
```

Runs on port `8001`. `--reload` enables hot reload on Python file changes. FastAPI's interactive API docs are available at `http://localhost:8001/docs`.

Health check: `curl http://localhost:8001/health`

### Notification (Node.js Lambda — LocalStack)

The Notification service is an AWS Lambda function. It is not run as a persistent process locally. Instead, it is deployed to LocalStack and invoked via SQS events.

```bash
cd services/notification
npm install        # first time only
npm run deploy:local   # packages and deploys to LocalStack
```

`deploy:local` runs `npm run build` and then uses the AWS CLI (pointing at LocalStack) to update the Lambda function code. Re-run this command whenever you change the function code.

After deployment, the function is triggered automatically when messages arrive on the LocalStack SQS `notifications` queue.

### Frontend (React / Vite)

```bash
cd services/frontend
npm install        # first time only
npm run dev
```

Runs on port `5173` (Vite's default). Points at `http://localhost:8080` (Auth), `http://localhost:3001` (Document), `http://localhost:8001` (AI), and `ws://localhost:3003` (Realtime) via `.env.local`.

---

## Full-stack containers (Compose profiles)

When you need all services running in Docker containers — to verify a Dockerfile, run integration tests, or reproduce a "works on my machine" issue — use the `services` Compose profile:

```bash
make up-all
```

This runs `docker compose --profile services up`, which brings up both the infrastructure services and the application service containers. The service definitions in `docker-compose.yml` are gated behind the `services` profile so they are not started by the default `make up`.

Stop everything:

```bash
make down-all
```

`make up-all` is not the recommended default workflow because it requires a Docker rebuild whenever code changes (`docker compose build <service>`). Use it for:
- Verifying that a service's Dockerfile builds and produces a working container.
- Running the integration test suite (which targets the containerised stack).
- Reproducing a CI failure locally.

---

## LocalStack configuration

LocalStack emulates the AWS services used by the async event flow: SNS, SQS, and Lambda. It does not emulate ECS, ECR, or IAM.

The AWS CLI must be configured with dummy credentials to interact with LocalStack:

```bash
aws configure set aws_access_key_id test --profile localstack
aws configure set aws_secret_access_key test --profile localstack
aws configure set region eu-central-1 --profile localstack
```

All LocalStack commands use `--endpoint-url http://localhost:4566 --profile localstack`. This is encapsulated in the `make` targets — you should not need to write raw AWS CLI commands for routine local dev.

LocalStack resources (SNS topics, SQS queues, Lambda functions) are created by a `make setup-local` command that runs the AWS CLI provisioning commands against the LocalStack endpoint. This command is idempotent — safe to re-run after `make reset`.

```bash
make setup-local
```

What `setup-local` creates:
- SNS topic: `document-events`
- SQS queue: `notifications` (with DLQ: `notifications-dlq`)
- SQS queue: `realtime-updates` (with DLQ: `realtime-updates-dlq`)
- SNS → SQS subscriptions for both queues
- Lambda function: `notification` (from the built package in `services/notification/dist/`)

---

## Database migrations

Each service that owns a relational schema runs its migrations independently.

### Auth & Workspace (Flyway)

Migrations run automatically when the Spring Boot service starts. Flyway detects any unapplied migrations in `src/main/resources/db/migration/` and applies them in version order. No manual command is required for normal development.

To run migrations manually (e.g., to check the schema state before starting the service):

```bash
cd services/auth-workspace
./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/auth_db \
  -Dflyway.user=collabspace -Dflyway.password=<from .env.local>
```

### AI Assistant (TBD)

The migration tool for the AI Assistant's PostgreSQL schema will be decided when the service is implemented. The `vector_db` database resides on the same PostgreSQL instance as `auth_db`, on a separate schema with separate database credentials.

---

## Environment variables

Each service reads its configuration from environment variables at startup. For local development, these are defined in `.env.local` files in each service directory. `.env.local` is gitignored — never commit it.

A `.env.example` file in each service directory documents every variable the service reads, with placeholder or safe default values. Copy `.env.example` to `.env.local` and fill in the values before starting the service.

```bash
cp services/auth-workspace/.env.example services/auth-workspace/.env.local
# edit the copy to fill in real values
```

### Common variables (all services)

| Variable | Description |
|---|---|
| `ENV` | Environment name: `development`, `staging`, `production` |
| `LOG_LEVEL` | Log level: `debug`, `info`, `warn`, `error` |
| `CORS_ALLOWED_ORIGIN` | Frontend origin for CORS (local: `http://localhost:5173`) |

### Auth & Workspace

| Variable | Description |
|---|---|
| `DB_URL` | PostgreSQL connection URL (`jdbc:postgresql://localhost:5432/auth_db`) |
| `DB_USER` | Database username |
| `DB_PASSWORD` | Database password |
| `REDIS_URL` | Redis connection URL (`redis://localhost:6379`) |
| `JWT_PRIVATE_KEY` | RSA private key (PEM-encoded). In AWS, this is read from SSM; locally, it is a file path or inline PEM. |
| `JWT_ISSUER` | JWT issuer claim (`http://localhost:8080` locally) |
| `JWT_AUDIENCE` | JWT audience claim (`collabspace-api`) |
| `BCRYPT_COST` | bcrypt cost factor (default: `12`) |

### Document Service

| Variable | Description |
|---|---|
| `MONGODB_URI` | MongoDB connection string (`mongodb://localhost:27017/documents`) |
| `AI_SERVICE_PUBLIC_KEY` | RSA public key of the AI Assistant (for service-to-service JWT validation) |

### AI Assistant

| Variable | Description |
|---|---|
| `VECTOR_DB_URL` | PostgreSQL connection URL for the vector database |
| `VECTOR_DB_USER` | Database username |
| `VECTOR_DB_PASSWORD` | Database password |
| `DOCUMENT_SERVICE_URL` | Document Service base URL (`http://localhost:3001`) |
| `AI_PRIVATE_KEY` | RSA private key for service-to-service JWT signing |
| `ANTHROPIC_API_KEY` | Claude API key for embedding and completion calls |

---

## Make targets reference

```
make up           — Start infrastructure containers (postgres, mongo, redis, localstack)
make down         — Stop and remove infrastructure containers (volumes preserved)
make reset        — Stop, remove volumes, restart, and re-run setup-local + migrations
make up-all       — Start infrastructure + application containers (--profile services)
make down-all     — Stop all containers including application services
make setup-local  — Create LocalStack resources (idempotent)
make logs         — Tail docker-compose logs for all infrastructure services
make logs s=<svc> — Tail logs for a specific service (e.g., make logs s=postgres)
```

---

## Typical development session

```bash
# Terminal 1: infrastructure
make up && make setup-local

# Terminal 2: auth service
cd services/auth-workspace && ./mvnw spring-boot:run

# Terminal 3: document service
cd services/document-service && npm run dev

# Terminal 4: frontend
cd services/frontend && npm run dev

# ... open additional terminals for realtime, AI assistant, notification as needed
```

Once the Walking Skeleton is complete (Stage 1), the minimum useful local stack for frontend development is: infrastructure (`make up`) + auth service + document service + frontend. The realtime service can be omitted if you are not working on live-collaboration features; the AI assistant can be omitted if you are not working on AI features.
