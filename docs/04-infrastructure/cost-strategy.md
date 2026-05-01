# Cost Strategy

## Target

**$0–5 per month.** CollabSpace is a learning project. Infrastructure cost is a constraint, not an afterthought. Every architectural decision with a cost dimension has been made with this target in mind. The strategy to hit it is not clever resource sizing — it is teardown discipline. See below.

---

## Free Tier Strategy

AWS free tier has two flavours: **12-month** (resets to paid after the first year) and **always free** (permanent). The table below notes which applies per resource. Managed services outside AWS (Atlas, Upstash) have their own permanent free tiers.

| Service          | Compute                | Key Data Store               | Free Tier                              | Always Free?  |
| ---------------- | ---------------------- | ---------------------------- | -------------------------------------- | ------------- |
| Auth & Workspace | ECS Fargate            | RDS PostgreSQL (`auth_db`)   | 50 vCPU-hr + 100 GB RAM-hr / month     | No (12-month) |
| Document Service | ECS Fargate            | MongoDB Atlas M0             | 512 MB cluster, no expiry              | Yes (Atlas)   |
| Realtime Service | EC2 t3.micro           | Redis via Upstash            | 750 instance-hr / month (shared pool)  | No (12-month) |
| AI Assistant     | ECS Fargate            | RDS PostgreSQL (`vector_db`) | Co-located on Auth RDS instance        | No (12-month) |
| Notification     | Lambda (Node 22)       | —                            | 1M invocations + 400K GB-sec / month   | Yes           |
| Kafka broker     | EC2 t3.micro           | — (disk only)                | 750 instance-hr / month (shared pool)  | No (12-month) |
| Messaging        | SNS + SQS              | —                            | 1M publishes + 1M requests / month     | Yes           |
| API entry        | API Gateway (HTTP API) | —                            | 1M calls / month                       | No (12-month) |
| Database host    | RDS db.t3.micro        | —                            | 750 instance-hr / month, 20 GB storage | No (12-month) |

**Notes:**

- **Fargate free tier** covers ~2 hours/day of a minimal task (0.25 vCPU, 0.5 GB RAM) across all Fargate workloads combined. With three Fargate services (Auth, Document, AI) running simultaneously, the free allocation is exhausted in under an hour. This makes teardown discipline non-optional. → See [Teardown Discipline](#teardown-discipline) below.

- **EC2 shared pool**: The Realtime Service and Kafka each need a t3.micro. Two instances running 24/7 consume ~1,440 instance-hours per month against a 750-hour free tier — exceeding it by ~$8/month. With teardown, both instances are stopped between sessions and the bill approaches $0. Stopping (not terminating) preserves attached EBS volumes and Kafka data.

- **RDS co-location**: Auth & Workspace and AI Assistant share a single `db.t3.micro` RDS instance with separate databases (`auth_db`, `vector_db`) and separate RDS users. A second RDS instance would cost ~$15/month outside free tier. Co-location keeps this within the single 750-hour free allocation. See ADR-005 for the revisit criteria (split when AI query load creates measurable latency impact on Auth). → ADR-005

- **MongoDB Atlas M0**: The only data store on a permanent free tier with no usage-based ceiling. 512 MB is the hard limit; v1 document storage for a team of 5–15 is well within this.

- **Lambda + SNS/SQS**: Permanently free at the volumes CollabSpace will generate. Even daily active use of the notification path would not approach the free tier ceiling.

---

## Worth Paying For

A small number of things are worth spending real money on if they unblock the learning goal:

| Item                                  | Cost                   | Why                                                                                          |
| ------------------------------------- | ---------------------- | -------------------------------------------------------------------------------------------- |
| Route 53 hosted zone                  | $0.50/month            | A real domain makes HTTPS, OIDC, and CORS configuration realistic rather than localhost-only |
| AWS Budgets alert                     | Free (first 2 budgets) | Peace of mind; no surprise bill                                                              |
| RDS snapshot before a risky migration | ~$0.02 / GB            | Cheap insurance during schema changes                                                        |

Everything else — larger instance types, multi-AZ, ElastiCache, Secrets Manager — is out of scope for v1 and would blow the budget immediately.

---

## Teardown Discipline

The primary cost control mechanism is not configuration — it is habit. The rule:

> **If you are not actively working on the project, nothing should be running except MongoDB Atlas, Upstash Redis, and Lambda (which have no idle cost).**

In practice this means:

- **End of every session:** run `terraform destroy` on the ECS services and stop (not terminate) the EC2 instances. EBS-backed stopped instances do not incur compute charges; only the EBS volumes (~$0.10/GB/month) accrue cost.
- **Start of every session:** run `terraform apply` to bring services back up. The full environment should be reproducible from Terraform state in under 5 minutes.
- **RDS:** AWS allows stopping an RDS instance for up to 7 days before it auto-restarts. Stop it between sessions; include RDS start/stop in the session workflow.
- **No "I'll leave it running overnight":** At Fargate pricing (~$0.04048/vCPU-hour), three services running overnight = ~$0.50. Across a month of evenings this is $10–15, which is 2–3× the monthly budget.

A `Makefile` with `make up` and `make down` targets will wrap these workflows once the Terraform modules exist. Until then, treat the session boundary discipline as a hard rule.

---

## Billing Alarm

A CloudWatch billing alarm set at **$5 USD** will be configured during the infrastructure bootstrap session (Stage 1). AWS Budgets provides the first two budget alerts free. Until then, check the AWS billing console manually at the start of each session.

> **Do not wait for the billing alarm to be set up before using AWS.** The teardown discipline above is sufficient protection at this stage. The alarm is a backstop, not the primary control.

---

## Cost Revisit Criteria

The $0–5/month target is valid while CollabSpace is in active development with one developer. Revisit this strategy when:

- The 12-month free tier windows begin to expire (mark the AWS account creation date)
- A second developer joins and doubles the active session time
- The AI Assistant's pgvector queries create measurable RDS load (see ADR-005 co-location revisit criteria)
- Atlas M0's 512 MB limit is approached (export metrics periodically)
