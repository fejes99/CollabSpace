# Module: alb

Creates the Application Load Balancer and its HTTP listener. This is the single public entry point for all HTTP traffic to the environment.

## What it creates

| Resource | Purpose |
|---|---|
| `aws_lb` | Internet-facing ALB in public subnets |
| `aws_lb_listener` (HTTP:80) | Accepts inbound connections; default action returns 404 |

This module creates **only** the ALB and listener — not target groups or listener rules. Those are created per-service in the `ecs-service` module. This separation keeps the ALB module stable: adding a new service never requires modifying it.

## How routing works

The listener's default action is a `404 Not Found` fixed response. Each service adds its own `aws_lb_listener_rule` (inside the `ecs-service` module) with a path-pattern condition. When a request arrives, the ALB evaluates rules in priority order — lowest number wins. If no rule matches, the 404 default fires.

**Why 404 and not 503?** 503 (Service Unavailable) implies the server is overloaded or temporarily down. 404 (Not Found) is correct: if no rule matches the path, that resource simply doesn't exist at this ALB.

## Security hardening

`drop_invalid_header_fields = true` rejects requests with malformed HTTP headers. This is a security best practice (blocks some HTTP request-smuggling vectors) and has no cost. AWS defaults it to `false` for backwards compatibility.

`enable_deletion_protection = false` in dev so `terraform destroy` can clean up cleanly. **Set this to `true` before the first production deployment.**

## Usage

```hcl
module "alb" {
  source = "../../modules/alb"

  project_name      = "collabspace"
  environment       = "dev"
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  alb_sg_id         = module.security_groups.alb_sg_id
}
```

## Inputs

| Variable | Type | Description |
|---|---|---|
| `project_name` | string | Prefix for resource names |
| `environment` | string | Environment name (dev, staging, prod) |
| `vpc_id` | string | VPC ID — required by target groups created outside this module |
| `public_subnet_ids` | list(string) | Public subnets for the ALB. Must span at least two AZs — AWS enforces this. |
| `alb_sg_id` | string | Security group ID for the ALB. Must allow inbound 80/443 from `0.0.0.0/0`. |

## Outputs

| Output | Used by |
|---|---|
| `alb_dns_name` | Public URL during walking skeleton phase — `terraform output alb_dns_name` to get it |
| `listener_arn` | `ecs-service` module — each service passes this to attach its listener rule |
| `alb_arn` | Future use: WAF ACL association, AWS Shield |
| `alb_zone_id` | Future use: Route 53 alias record pointing a custom domain at the ALB |
