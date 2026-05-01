# ADR-013: ECS over Kubernetes

**Status:** Accepted
**Date:** 2026-05-01

---

## Context

CollabSpace runs five containerized services on AWS. Choosing a container orchestrator is a foundational infrastructure decision: it determines the operational model, cost structure, CI/CD integration points, and the transferable skills gained from building and running the system.

The two realistic options for container orchestration on AWS are Amazon ECS (the AWS-native scheduler) and Kubernetes (either managed via Amazon EKS or self-managed on EC2). The Walking Skeleton is the decision point — once CI/CD is built around one orchestrator, the tooling, IAM policies, task/pod definitions, and deployment scripts are all coupled to that choice. Switching mid-project means rebuilding most of the infrastructure layer.

The budget target for the dev environment is $0–5/month total. → See [infrastructure/environments/dev/README.md](../../infrastructure/environments/dev/README.md).

---

## Decision

Use **Amazon ECS** as the container orchestrator — Fargate for stateless services (Auth & Workspace, Document Service, AI Assistant), EC2 for the Realtime Service and Kafka broker. → See [ADR-005](adr-005-heterogeneous-compute.md) for the per-service compute decisions.

Kubernetes is not used in v1. If the project graduates to a stage where Kubernetes skills or ecosystem tooling become active requirements, a port-from-ECS exercise is the natural follow-on project.

---

## Alternatives Considered

**Amazon EKS (managed Kubernetes)**

EKS eliminates the control plane operational burden by managing the Kubernetes API server, etcd cluster, and controller manager as a managed AWS service. The trade-off is cost: the EKS control plane is billed at $0.10/hour regardless of whether any nodes are running — approximately **$73/month**. The entire CollabSpace dev environment targets $0–5/month total. The control plane alone would be roughly 15× the budget ceiling before a single node, task, or log group is provisioned.

Beyond cost, EKS introduces operational concepts that are not required to achieve the project's goals at this stage: pod disruption budgets, node groups and managed node group lifecycle, the horizontal pod autoscaler, cluster-level RBAC distinct from AWS IAM, kube-proxy networking, and CoreDNS. None of these concepts map to a concrete learning goal in v1. They are valuable knowledge, but learning them on top of AWS, Spring Boot, and the application domain simultaneously is unnecessary cognitive load.

**Self-managed Kubernetes on EC2**

Running the Kubernetes control plane on EC2 eliminates the $73/month EKS charge. The trade-off is that the control plane consumes EC2 instances — typically three for etcd quorum — and requires manual certificate rotation, etcd backup, version upgrades, and control plane monitoring. The operational burden of maintaining a Kubernetes control plane is roughly proportional to the project it is meant to serve. Investing that effort in the control plane itself, rather than the application, inverts the learning priorities.

Self-managed Kubernetes is the right choice when EKS cost is prohibitive and Kubernetes-specific features (CRDs, the full operator pattern, specific admission webhooks) are hard requirements. Neither condition applies here.

**k3s (lightweight Kubernetes)**

k3s is a CNCF-certified, production-grade lightweight Kubernetes distribution. It bundles the control plane into a single binary, can run on a small EC2 instance (t3.micro is viable for light workloads), and passes the Kubernetes conformance test suite. This eliminates the EKS cost problem and reduces the control plane surface area considerably compared to upstream Kubernetes.

k3s is a reasonable choice for teams who already operate Kubernetes and need a low-cost dev cluster with K8s compatibility. For this project, the goal is to learn AWS-native infrastructure patterns: IAM task roles, ALB target group integration, ECR authentication, and CloudWatch log routing. k3s provides Kubernetes API compatibility but does not teach these AWS-specific integration patterns — they work differently in k3s than they do in EKS, and differently still than in ECS. The learning return for the project's goals is lower than ECS.

---

## Consequences

**Positive:**
+ ALB target group integration is native to ECS — no Ingress controller, load balancer controller operator, or annotation-based routing configuration required.
+ Task IAM roles give each ECS task a scoped AWS identity without the complexity of IRSA (IAM Roles for Service Accounts) or the EKS Pod Identity webhook. The IAM model is the same one used everywhere else in AWS.
+ The CloudWatch log driver is built into the ECS task definition (`awslogs` driver). Logs flow to CloudWatch without a sidecar, DaemonSet, or log shipping agent.
+ ECR authentication is handled automatically by the ECS task execution role — no `imagePullSecret` rotation.
+ No control plane cost. The ECS scheduler is a free AWS service.
+ The ECS resource model (task definition → service → target group) maps directly to the Terraform modules already in the repository. The equivalent Kubernetes manifest set (Deployment, Service, Ingress, HPA, PodDisruptionBudget) is significantly larger for the same outcome.

**Negative:**
- ECS is AWS-proprietary. The operational skills and tooling are not transferable to GCP, Azure, or on-premises deployments. Kubernetes skills are broadly transferable across cloud providers and on-premises environments.
- The Kubernetes ecosystem is substantially larger: Helm, Argo CD, Flux, Karpenter, Crossplane, a mature service mesh ecosystem. These tools are not needed for v1, but the ecosystem gap is real if the project grows beyond a single-account AWS deployment.
- If this project is later extended to a multi-cloud or hybrid deployment model, migrating from ECS to Kubernetes is non-trivial.

---

## Revisit when

- Moving to a staging or production environment where operational portability or the Kubernetes ecosystem tooling (Helm charts for service distribution, GitOps via Argo CD) become active requirements.
- The team grows to include engineers with existing Kubernetes expertise who can share the operational burden.
- AWS introduces a meaningful pricing change to EKS that closes the cost gap with ECS.
