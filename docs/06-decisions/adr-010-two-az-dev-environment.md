# ADR-010: Two Availability Zones for the Dev Environment

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

AWS Availability Zones (AZs) are physically isolated data centers within a region. Distributing resources across multiple AZs protects against a single-facility failure. The question is how many AZs to use in the dev environment.

AWS recommends three AZs for production workloads to achieve the highest availability. `eu-central-1` (Frankfurt) offers three AZs: `eu-central-1a`, `eu-central-1b`, `eu-central-1c`.

### Alternatives considered

**Option A — Single AZ**
- Cheapest. One public subnet, one private subnet.
- Any AZ-level failure takes down the entire dev environment.
- Not representative of multi-AZ architecture. Learning value is low.

**Option B — Two AZs (chosen)**
- One public subnet and one private subnet per AZ: four subnets total.
- An ALB requires at least two AZs to be created — this is an AWS hard requirement.
- A two-AZ deployment teaches the core concepts: subnet spanning, AZ-aware resource placement, ALB cross-AZ routing.
- Failure of one AZ keeps the environment partially operational.

**Option C — Three AZs**
- Matches AWS production recommendations.
- Six subnets. More complex Terraform (for_each over AZ list).
- Additional ALB cross-AZ data transfer charges when services call each other across AZs.
- Marginal additional resilience for a dev environment that has no SLA.
- The jump from two to three AZs is a CIDR-planning and module variable change — not an architectural redesign.

---

## Decision

The dev environment uses **two Availability Zones**: `eu-central-1a` and `eu-central-1b`.

Subnets:
```
Public  eu-central-1a  10.0.1.0/24
Public  eu-central-1b  10.0.2.0/24
Private eu-central-1a  10.0.11.0/24
Private eu-central-1b  10.0.12.0/24
```

The VPC module accepts `azs` and `public_subnet_cidrs` / `private_subnet_cidrs` as list variables, so adding a third AZ later is a variable-value change with no module restructuring required.

---

## Consequences

**Positive:**
- Satisfies the ALB two-AZ minimum without over-engineering.
- Teaches multi-AZ concepts (subnet spanning, AZ-aware scheduling) at minimal complexity.
- Four subnets is a manageable mental model for a learning project.
- CIDR space is pre-planned to accommodate a third AZ without renumbering.

**Negative:**
- One AZ short of AWS production recommendation. A real outage affecting both `eu-central-1a` and `eu-central-1b` simultaneously (extremely rare, but not impossible) would fully take down the dev environment.
- Does not teach three-AZ patterns (e.g., quorum-based distributed systems that prefer odd AZ counts).

---

## Revisit when

- Promoting any service from dev to a production environment — switch to three AZs at that point.
- Adding a database that requires a Multi-AZ deployment (RDS Multi-AZ needs at least two AZs; this is already satisfied).
