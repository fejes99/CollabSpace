# ADR-012: Generic ECS Service Module with CI/CD-Managed Task Definitions

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

CollabSpace runs four ECS Fargate services (auth-workspace, document-service, realtime-service, ai-assistant). Each needs a task definition, an ECS service, a target group, and a listener rule. These four resources have the same structure across services; only the values differ (image URL, port, CPU, memory, paths).

Two sub-decisions are bundled here:

1. **How to structure the Terraform code** — one generic module vs. per-service definitions.
2. **Who manages the task definition after initial creation** — Terraform on every apply vs. CI/CD on every deploy.

These decisions interact: a generic module makes the lifecycle decision explicit and reusable.

---

## Decision 1: Generic `modules/ecs-service/` Module

A single reusable module (`infrastructure/modules/ecs-service/`) creates all four resources (target group, listener rule, task definition, ECS service) for any service. Each service is instantiated with one `module` block in `environments/dev/main.tf`.

**Why this over per-service task definitions:**
- Avoids writing the same four resources four times with copy-paste differences.
- The module-per-concept principle from CLAUDE.md: the concept is "an ECS Fargate service behind an ALB", not "auth-workspace".
- Adding a new service requires one `module` block call, not duplicating a file.
- The interface (input variables) documents exactly what is configurable per service.

**Module boundary:** The module does not create the ECS cluster, ALB, VPC, or IAM roles. These are shared across services and created by their own modules. The ecs-service module only creates the per-service resources that are unique to one service.

---

## Decision 2: Terraform Creates, CI/CD Updates

Terraform creates the **initial** task definition (revision 1) and ECS service. After that, the CI/CD pipeline is responsible for deploying new images:

1. GitHub Actions builds the container image and pushes it to ECR with a git SHA tag.
2. The pipeline calls `aws ecs register-task-definition` with the new image URL (revision 2, 3, ...).
3. The pipeline calls `aws ecs update-service --force-new-deployment` to roll out the new revision.

To prevent `terraform apply` from resetting the ECS service back to revision 1, the task definition is listed in `ignore_changes`:

```hcl
lifecycle {
  ignore_changes = [task_definition]
}
```

**Why this split instead of Terraform managing everything:**

If Terraform managed the image tag, the tag would need to be a variable. Changing the tag would require a `terraform apply`. That creates a dependency: engineers cannot deploy without Terraform access, and the state file becomes the source of truth for which image is running. CI/CD tools (GitHub Actions) are better positioned for this — they have the git context, the ECR credentials, and can run on every commit.

**Why `ignore_changes` over a separate "deployment" module:**
A separate Terraform module for deployments (using a `null_resource` + `local-exec` to call the AWS CLI) is a common pattern but couples Terraform to the deployment mechanism. If the pipeline changes (e.g., switching from GitHub Actions to CodePipeline), the Terraform code would also need to change. `ignore_changes` cleanly separates concerns: Terraform manages infrastructure shape, CI/CD manages what runs on it.

---

## Alternatives Considered

**Per-service task definitions (no shared module)**
- Explicit: every service's task definition is in its own file.
- Cost: four nearly identical sets of four resources. Any change to the shared structure (e.g., adding a new environment variable) requires updating all four files.
- Rejected: violates the module-per-concept principle and creates maintenance debt.

**Terraform manages image tags via variable + tfvars**
- Every deploy runs `terraform apply -var image_tag=abc1234`.
- Keeps all state in Terraform — the state file always reflects what's running.
- Rejected: requires Terraform state access for every deploy. Adds friction to CI/CD. Terraform apply is slower than `aws ecs update-service`. `ignore_changes` is the idiomatic Terraform pattern for this separation.

**AWS CodeDeploy blue/green deployments**
- Zero-downtime deployments: new task set runs in parallel, traffic shifts gradually.
- Requires ECS service `deployment_controller = { type = "CODE_DEPLOY" }`.
- Cost: CodeDeploy is free; the cost is the double task count during deployment (~15 minutes of 2× Fargate cost).
- Rejected for the walking skeleton: adds significant complexity. Revisit when the service is handling real traffic and downtime is a concern.

---

## Consequences

**Positive:**
- One module call per service in `environments/dev/main.tf`. Adding document-service is one `module` block.
- The CI/CD pipeline is the deployment authority. Terraform applies can run at any time without accidentally reverting a deployment.
- `ignore_changes` is well-understood Terraform pattern; it is explicit in the lifecycle block so future maintainers know it is intentional.

**Negative:**
- The Terraform state does not reflect the currently-running image tag. `terraform state show module.auth_workspace.aws_ecs_service.service` shows revision 1, not the deployed revision. To see the real deployed state, you must use the AWS console or `aws ecs describe-services`.
- `ignore_changes` on task_definition means Terraform will not prevent drift if someone manually updates the ECS service through the console. This is acceptable in a learning environment.

---

## Revisit when

- Adding a second service: confirm the module interface is sufficient without changes.
- Implementing blue/green deployments: the `deployment_controller` type is mutually exclusive with rolling deployments and requires the service to be destroyed and recreated.
- Moving to production: evaluate whether `ignore_changes` is appropriate or whether a tighter Terraform-managed deploy pipeline is preferred.
