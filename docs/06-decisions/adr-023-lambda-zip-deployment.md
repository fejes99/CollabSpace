# ADR-023: Lambda ZIP Deployment for Notification Service

**Status:** Accepted
**Date:** 2026-05-14

## Context

The notification service is implemented as an AWS Lambda function (Node.js 24). Lambda supports two artifact types for deployment:

1. **ZIP package** — the original Lambda deployment mechanism since 2014. A compiled/bundled `.zip` file is uploaded directly or via S3, and Lambda runs it on a managed runtime.
2. **Container image** — added in 2020. A Docker image is pushed to ECR, and Lambda pulls and runs it using a Lambda-specific container runtime.

The other four services in this platform (auth-workspace, document-service, realtime-service, ai-assistant) all use Docker container images deployed to ECS Fargate, which might suggest using container images for Lambda too.

The notification service is an event-driven handler: it will consume SNS/SQS fan-out events and dispatch notifications (email, push, etc.). It does not run a persistent HTTP server.

## Decision

Use **ZIP package deployment** for the notification Lambda.

The build pipeline compiles TypeScript with esbuild into a single bundled JS file, zips it, and uploads the archive directly via `aws lambda update-function-code`. No ECR involvement.

## Alternatives Considered

### Container image (ECR → Lambda)

- Consistent artifact type with the four ECS services.
- Supports images up to 10 GB (vs 250 MB unzipped for ZIP).
- Enables OS-level package control and custom runtimes.
- **Rejected because:** Lambda container images incur ECR storage costs beyond the free tier and have slower cold starts than ZIP. The notification service has no heavy native dependencies, no large ML models, and no need for OS-level control. The "visual consistency" argument does not hold — Lambda's execution model (event-driven, ephemeral, no persistent server) is fundamentally different from ECS. Sharing a Dockerfile pattern between ECS and Lambda provides no practical benefit.

### ZIP with S3 artifact storage

- Stores versioned `.zip` artifacts in S3 before deploying, enabling artifact pinning and rollback to any prior version.
- Better audit trail than direct upload.
- **Deferred:** adds an S3 bucket resource and more complex CI. Appropriate once the service is out of the walking skeleton phase and rollback matters. The direct-upload path (`--zip-file`) supports archives up to 69 MB, which a bundled Node.js notification handler will not approach.

## Consequences

**+** Faster cold starts — Lambda's managed runtime is optimised for ZIP.

**+** Simpler CI pipeline — build, zip, `update-function-code`. No ECR push step.

**+** No ECR storage cost for Lambda artifacts.

**+** Idiomatic — ZIP is the standard for lightweight event-driven Node.js Lambdas across the industry.

**−** Breaks the Docker-everywhere pattern of the other four services. A developer joining this project needs to understand that Lambda uses a different artifact type.

**−** 250 MB unzipped size ceiling. If the notification service later requires large native dependencies (e.g., a PDF renderer, image processing), migration to container image would be necessary.

**−** No artifact versioning or rollback path in the initial implementation. A prior deployment cannot be re-activated without re-running CI with a reverted commit — a longer recovery window than a Lambda alias swap or S3 version pin.

**−** esbuild bundles TypeScript into a single JS file and tree-shakes unused code, which keeps the archive small. However, native `.node` binary addons cannot be bundled — if a transitive dependency introduces one, the zip will fail at runtime. This is unlikely for a Node.js notification handler but is a silent failure mode worth knowing.

## Revisit when

- Lambda dependencies approach 200 MB unzipped.
- A custom runtime or native OS packages become necessary.
- A rollback-to-prior-version capability is required — implement S3 artifact storage with versioned keys at that point, not container images.
- A transitive dependency introduces a native `.node` binary (esbuild cannot bundle these; switch to a zip-with-node_modules approach or container image).
