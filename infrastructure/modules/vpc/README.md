# Module: vpc

Creates the VPC and core network infrastructure shared by all services in an environment.

## What it creates

| Resource | Purpose |
|---|---|
| VPC (`10.0.0.0/16`) | Isolated network boundary for all environment resources |
| Internet Gateway | Single exit point from the VPC to the public internet |
| Public subnet × N | Hosts the ALB and ECS Fargate tasks (one per AZ) |
| Private subnet × N | Hosts RDS PostgreSQL — no internet route (one per AZ) |
| Public route table | Routes `0.0.0.0/0` → IGW for public subnets |
| Route table associations | Attach the public route table to each public subnet |
| S3 VPC gateway endpoint | Routes ECR image-layer (S3) traffic through AWS backbone — free |

Private subnets use the VPC's default route table, which only has the implicit local route. They cannot reach the internet — intentional.

## Why ECS tasks are in public subnets

ECS tasks are placed in public subnets and assigned public IPs by `map_public_ip_on_launch = true`. This allows outbound internet access (ECR, CloudWatch, SSM) through the IGW without a NAT Gateway.

A NAT Gateway costs ~$32/month — incompatible with the $0–5/month budget target. Security groups compensate: ECS tasks accept inbound only from the ALB security group, so their public IPs are not directly reachable.

See [ADR-009](../../../docs/06-decisions/adr-009-ecs-public-subnet-strategy.md) for full analysis.

## Usage

```hcl
module "vpc" {
  source = "../../modules/vpc"

  project_name = "collabspace"
  environment  = "dev"

  azs                  = ["eu-central-1a", "eu-central-1b"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
}
```

## Adding a third AZ (when promoting to production)

Add the AZ and one CIDR to each list — no module restructuring required:

```hcl
azs                  = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
```

**Never reorder the AZ list.** The `for_each` key is the list index (`"0"`, `"1"`, …). Reordering causes Terraform to destroy and recreate subnets. Only ever append new AZs at the end.

## Inputs

| Variable | Type | Default | Description |
|---|---|---|---|
| `project_name` | string | — | Prefix for resource names |
| `environment` | string | — | Environment name (dev, prod) |
| `vpc_cidr` | string | `10.0.0.0/16` | VPC CIDR block |
| `azs` | list(string) | — | Availability Zone names |
| `public_subnet_cidrs` | list(string) | — | One CIDR per AZ for public subnets |
| `private_subnet_cidrs` | list(string) | — | One CIDR per AZ for private subnets |

## Outputs

| Output | Used by |
|---|---|
| `vpc_id` | Every other module that creates VPC-scoped resources |
| `public_subnet_ids` | ALB, ECS services |
| `private_subnet_ids` | RDS subnet groups, ElastiCache (if ever added) |
| `public_route_table_id` | Any future VPC gateway endpoint additions |
