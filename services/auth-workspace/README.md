# auth-workspace

Authentication and workspace management service. Handles user registration, login, JWT issuance, and workspace RBAC. Built with Java 25 + Spring Boot 4.

**Current state:** Walking skeleton — deployed and healthy. `/actuator/health` returns `200 OK` from ECS Fargate via the ALB. Full auth endpoints are not yet implemented.

## What it does (walking skeleton)

- `GET /actuator/health` — returns `{"status":"UP"}`. Used by the ALB health check to determine if the task is healthy.

## Running locally

```bash
cd services/auth-workspace
./mvnw spring-boot:run
```

The service starts on port 8080. Visit `http://localhost:8080/actuator/health` to verify.

## Running tests

```bash
./mvnw test
```

## Building the Docker image

```bash
docker build --platform linux/amd64 -t auth-workspace:local .
docker run -p 8080:8080 auth-workspace:local
```

## Environment variables

None required for the walking skeleton. Future variables (database URL, JWT signing key, etc.) will be injected via SSM Parameter Store at task start.

## Deployment

Pushes to `main` that touch `services/auth-workspace/**` trigger `.github/workflows/service-auth.yml`, which:

1. Runs `./mvnw test`
2. Builds and pushes the Docker image to ECR (`collabspace-auth-workspace`) tagged `:<short-sha>` (ECR tags are immutable; `:skeleton` was a one-time bootstrap tag pushed on the first CI run)
3. Registers a new ECS task definition revision with the SHA-tagged image
4. Updates the `collabspace-dev-auth-workspace` ECS service and waits for stability

The service is reachable at the ALB DNS name once healthy:

```bash
cd infrastructure/environments/dev
terraform output alb_dns_name
```

## Architecture decisions

- [ADR-002](../../docs/06-decisions/adr-002-auth-workspace-combined.md) — auth and workspace combined into one service
- [ADR-012](../../docs/06-decisions/adr-012-terraform-cicd-task-definition-ownership.md) — Terraform creates the initial task definition; CI/CD manages revisions after that
