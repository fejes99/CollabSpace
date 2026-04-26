# ADR-009: ECS Tasks in Public Subnets Instead of NAT Gateway

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

ECS Fargate tasks need outbound internet access to:
- Pull container images from ECR
- Publish logs to CloudWatch Logs
- Read configuration from SSM Parameter Store

The standard production pattern puts tasks in **private subnets** and routes their outbound traffic through a **NAT Gateway** — a managed AWS service that translates private IPs to a public IP for outbound connections. This keeps tasks completely unreachable from the internet by default.

The problem: a NAT Gateway costs ~$0.045/hour (~$32/month) plus $0.045 per GB of data processed. CollabSpace targets $0–5/month for its AWS infrastructure. A NAT Gateway alone would consume 6–7× the monthly budget.

### Alternatives considered

**Option A — Private subnets + NAT Gateway (standard production pattern)**
- Tasks have no public IP; fully isolated from internet by default.
- Outbound goes through NAT → IGW.
- Cost: ~$32/month. Hard budget violation.

**Option B — Private subnets + NAT instance (t2.micro)**
- A community AMI (fck-nat or amzn-ami-vpc-nat) acts as a software NAT.
- Eligible for AWS free tier (750 hours/month) in the first 12 months.
- After free tier: ~$8/month.
- Drawbacks: manual patching, no auto-scaling, introduces an EC2 SPOF, adds operational complexity not worth bearing in a learning environment.

**Option C — Private subnets + VPC Interface Endpoints**
- Skip NAT entirely. Route traffic to ECR, CloudWatch, and SSM via interface endpoints that live inside the VPC.
- S3 gateway endpoint (ECR uses S3 for image layers): free.
- Interface endpoints for ECR API, ECR DKR, CloudWatch Logs, SSM: ~$0.01/hour/AZ each.
- Cost for 4 endpoints × 2 AZs × $0.01 × 730 hours ≈ **$58/month**. Significantly worse than NAT.

**Option D — Public subnets + assign_public_ip (chosen)**
- Tasks are placed in public subnets. ECS assigns each task a public IP at launch.
- Outbound traffic goes directly through the Internet Gateway — no NAT needed, no cost.
- Inbound is controlled entirely by security groups: tasks only accept traffic from the ALB security group on specific service ports. Everything else is denied.
- The public IP is effectively a send-only address: nothing external knows it, and the SG blocks all unsolicited inbound connections.

---

## Decision

ECS Fargate tasks run in **public subnets** with `assign_public_ip = true`.

RDS (PostgreSQL) remains in **private subnets** with no route to the internet. Upstash Redis is an external SaaS accessed over the public internet (outbound port 6379); it has no presence in the VPC and requires no private subnet. Traffic between ECS tasks and databases is purely intra-VPC routing, which is free and never leaves AWS.

An S3 gateway endpoint is provisioned in the public route table. This routes ECR image-layer traffic (which uses S3 internally) through the AWS backbone rather than the public internet — free, and avoids unnecessary data-transfer charges.

---

## Security posture

The security group on ECS tasks:
- **Inbound:** only from the ALB security group, on the service's listening port.
- **Outbound:** 443 to `0.0.0.0/0` (ECR, CloudWatch, SSM), plus any port needed for database access within the VPC.

A task's public IP is never registered in DNS, published to users, or reachable via the ALB listener. The only path from the internet to a task is: internet → ALB (port 80/443) → ALB security group → task security group → task container port.

---

## Consequences

**Positive:**
- Infrastructure cost: $0 for NAT. Stays within the $0–5/month budget.
- Simpler mental model: one fewer network layer to reason about during learning.
- No SPOF introduced (NAT instance option would have added one).

**Negative:**
- Each task has a public IP. A misconfigured or overly-permissive security group rule could accidentally expose a task port directly to the internet. This risk does not exist in the private-subnet model.
- Not representative of production best practice. Teams using this repo as a reference for production workloads must replace public subnets + assign_public_ip with private subnets + NAT Gateway before handling real user data.
- Migrating to private subnets later requires: adding a NAT Gateway (or instance), updating the ECS service network configuration, and verifying outbound connectivity. It is a one-afternoon change, but it is a change.

---

## Revisit when

- Migrating any service to production or handling real user data.
- AWS Free Tier eligibility is available and a NAT instance becomes cost-neutral (revisit Option B at that point).
- A service requires a static outbound IP (e.g., for a third-party IP allowlist) — NAT Gateway is the only clean solution.
