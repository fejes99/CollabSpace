# Module: security-groups

Creates all security groups and their rules for an environment. Centralising them in one module makes the inter-component trust relationships visible in one place.

## What it creates

| Security group | Allows inbound from | Allows outbound to |
|---|---|---|
| `alb` | `0.0.0.0/0` on 80, 443 | `ecs_tasks` SG on all ports |
| `ecs_tasks` | `alb` SG on all ports | anywhere on 443; `rds` SG on 5432 |
| `rds` | `ecs_tasks` SG on 5432 | (none declared) |

### Why no Redis security group?

Redis is provided by **Upstash**, an external SaaS service. Upstash has no presence inside the VPC, so there is no security group to create. ECS tasks reach Upstash outbound over the public internet on port 6379. That egress rule is added per-service when the first service using Redis is built, scoped to Upstash's published IP ranges.

This is different from RDS, which runs inside the VPC (private subnets) and requires a security group to control access.

### Security group referencing

Rules use `referenced_security_group_id` rather than CIDR blocks for intra-VPC traffic. This means "traffic from any ENI that carries this security group", which remains correct as the ALB or ECS tasks scale out and acquire new network interfaces with new IPs.

### Resource-per-rule approach

Each ingress/egress rule is a separate `aws_vpc_security_group_ingress_rule` / `aws_vpc_security_group_egress_rule` resource rather than inline `ingress {}` blocks inside the security group resource. This lets Terraform add or remove individual rules non-destructively — no need to destroy and recreate the security group when changing a single rule.

## Usage

```hcl
module "security_groups" {
  source = "../../modules/security-groups"

  project_name = "collabspace"
  environment  = "dev"
  vpc_id       = module.vpc.vpc_id
}
```

## Inputs

| Variable | Type | Description |
|---|---|---|
| `project_name` | string | Prefix for resource names |
| `environment` | string | Environment name (dev, prod) |
| `vpc_id` | string | VPC in which to create the security groups |

## Outputs

| Output | Used by |
|---|---|
| `alb_sg_id` | ALB resource, and the `ecs_tasks` SG egress rule |
| `ecs_tasks_sg_id` | ECS service network configuration |
| `rds_sg_id` | RDS instance, and the `ecs_tasks` SG egress rule |
