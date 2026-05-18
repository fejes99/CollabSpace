# Cost Strategy

## Target

**$0–5 per month.** CollabSpace is a learning project. Infrastructure cost is a constraint, not an afterthought. Every architectural decision with a cost dimension has been made with this target in mind. The strategy to hit it is not clever resource sizing — it is teardown discipline. See below.

---

## Free Tier Status

The AWS 12-month free tier **expired in May 2026**. All services previously covered by the 12-month window are now billed at full on-demand rates.

Always-free tiers that remain active:

| Service | Always-free limit |
| --- | --- |
| Lambda | 1M invocations + 400K GB-sec/month |
| SNS | 1M publishes/month |
| SQS | 1M requests/month |
| MongoDB Atlas M0 | 512 MB storage, permanent |
| Upstash Redis | 10K commands/day (Upstash free plan) |

---

## Actual Costs (no free tier)

> **Public IPv4 charges:** AWS introduced a $0.005/hr per public IPv4 address charge in February 2024. This was not in the original cost strategy. It applies to every ECS task with `assign_public_ip = true` and to each ALB node (one per AZ). With 2 AZs, the ALB alone accounts for 2 persistent IPs while the environment is live.

### Hourly rates while dev environment is running (eu-central-1)

| Resource | Rate | 1 service | 4 services |
| --- | --- | --- | --- |
| Fargate per task (0.25 vCPU, 0.5 GB) | $0.04048/vCPU-hr + $0.004445/GB-hr | $0.012/hr | $0.049/hr |
| ALB (fixed hourly) | $0.0225/hr | $0.023/hr | $0.023/hr |
| Public IPv4 — ALB (2 nodes) | $0.005/IP/hr | $0.010/hr | $0.010/hr |
| Public IPv4 — ECS tasks | $0.005/IP/hr | $0.005/hr | $0.020/hr |
| RDS db.t3.micro | $0.018/hr | $0.018/hr | $0.018/hr |
| **Total** | | **~$0.068/hr** | **~$0.120/hr** |

### Monthly estimate at typical pace (72 active hours/month)

72 hours = 4 hours/day × 18 working days — matches the May 2026 billing period.

| Configuration | Est. monthly cost | Status |
| --- | --- | --- |
| 4 services running (pre-optimization) | ~$8.60 | was current |
| 1 service running — Option 1 | ~$4.90 | **done** |
| 1 service + API Gateway instead of ALB — Option 2 | ~$2.60 | **done** |
| 1 service + API Gateway + Neon instead of RDS — Option 3 | ~$1.30 | planned |

---

## Worth Paying For

A small number of things are worth spending real money on if they unblock the learning goal:

| Item | Cost | Why |
| --- | --- | --- |
| Route 53 hosted zone | $0.50/month | A real domain makes HTTPS, OIDC, and CORS configuration realistic rather than localhost-only |
| AWS Budgets alert | Free (first 2 budgets) | Peace of mind; no surprise bill |
| RDS snapshot before a risky migration | ~$0.02/GB | Cheap insurance during schema changes |

Everything else — larger instance types, multi-AZ, ElastiCache, Secrets Manager — is out of scope for v1 and would blow the budget immediately.

---

## Teardown Discipline

The primary cost control mechanism is habit. The rule:

> **If you are not actively working on the project, nothing should be running.**

Session boundary workflow (all targets exist in the root `Makefile`):

```
# Start of session
make dev-up                              # provisions full environment (~5 min)
make dev-pause                           # scale all ECS services to 0
make dev-start s=<service-being-built>   # start only the service you need

# Mid-session break (under ~1 hour)
make dev-pause                           # stops Fargate billing; ALB and RDS still run

# End of session
make dev-down                            # terraform destroy — stops all billing
```

**Why `dev-start` instead of `dev-resume`:** `dev-resume` starts all four services. Starting only the service being actively built reduces the hourly rate from ~$0.12 to ~$0.07 and keeps the monthly estimate within the $5 target at typical session lengths.

**No overnight runs:** With no free tier, the full 4-service environment running overnight costs ~$0.96. A week of forgotten overnight sessions = ~$6.72, which exceeds the monthly budget on its own.

---

## Billing Alarm

Configure a CloudWatch billing alarm at **$5 USD** via AWS Budgets (first two alerts are free). Check the AWS Billing console at the start of each session until the alarm is set up.

---

## Planned Optimizations

Two infrastructure changes are planned to reduce costs further once auth-workspace is functional and the single-service session workflow is stable.

### Option 2 — Replace ALB with API Gateway HTTP API

**Why:** The ALB costs $0.0225/hr fixed plus 2 public IPv4 IPs ($0.010/hr) — roughly $0.033/hr regardless of whether tasks are running. API Gateway HTTP API charges $1 per million requests, which is effectively $0 at development scale. Replacing the ALB eliminates ~$2.40/month at the current session pace.

**How:** Replace the `modules/alb` Terraform module with an API Gateway HTTP API + VPC Link. Each ECS service registers as a VPC Link integration instead of an ALB target group. Path-based routing moves to API Gateway routes.

**Caveat:** `realtime-service` uses WebSockets. HTTP API does not support WebSocket upgrades — that service needs an API Gateway **WebSocket API** resource, which is a separate Terraform module and resource type. The correct split is HTTP API for the four REST services and WebSocket API for realtime-service.

**Expected savings:** ~$2.40/month. Brings the monthly estimate from ~$4.90 to ~$2.60.

---

### Option 3 — Replace RDS with Neon (serverless Postgres, permanent free tier)

**Why:** RDS db.t3.micro costs $0.018/hr while running. At 72 session hours/month that is ~$1.30/month in compute alone. More critically, `terraform destroy` at session end **drops the database** — all data is lost on every `make dev-down`. This is acceptable now (no real data yet), but breaks the workflow the moment user registration is implemented and rows start accumulating.

**How:** Create a Neon project (neon.tech). Store the JDBC connection string in SSM under `/collabspace/dev/db/url`. Remove `aws_db_instance.main` from Terraform and replace it with an `aws_ssm_parameter` pointing to Neon. Flyway/Liquibase runs migrations on application startup, bringing the schema up automatically from a cold database each time.

**Benefits beyond cost:**
- Database persists between sessions — no data loss on `make dev-down`
- Neon scales to zero when idle — no charge between sessions
- Schema branching available for testing risky migrations

**Expected savings:** ~$1.30/month. Brings the monthly estimate from ~$2.60 to ~$1.30 (after Option 2).

**Prerequisite:** Do this before implementing user registration — that is the first feature that writes persistent rows.

---

## Cost Revisit Criteria

The $0–5/month target is valid while CollabSpace is in active development with one developer. Revisit this strategy when:

- ~~The 12-month free tier windows begin to expire~~ — **triggered May 2026; see Actual Costs section above**
- A second developer joins and doubles the active session time
- The AI Assistant's pgvector queries create measurable RDS load (see ADR-005 co-location revisit criteria)
- Atlas M0's 512 MB limit is approached (export metrics periodically)
