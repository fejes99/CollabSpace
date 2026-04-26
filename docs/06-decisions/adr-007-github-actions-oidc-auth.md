# ADR-007: GitHub Actions Authentication via OIDC Federation

**Status:** Accepted
**Date:** 2026-04-26

---

## Context

CI/CD pipelines need to interact with AWS resources: pushing Docker images to ECR and, in later stages, deploying to ECS. These operations require AWS credentials.

The standard legacy approach is to create an IAM user, generate a long-lived access key pair, and store those as GitHub Actions secrets (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`). This works, but introduces a class of risks that are well-documented and frequently exploited.

GitHub Actions supports OpenID Connect (OIDC), a federation protocol that lets a workflow prove its identity to AWS without any pre-shared secret. AWS has supported OIDC identity providers since 2021 and explicitly recommends this pattern for CI/CD.

---

## Decision

Use **GitHub Actions OIDC federation** to authenticate CI/CD pipelines to AWS. No IAM user is created. No long-lived credentials are stored in GitHub secrets.

The mechanism:

1. GitHub acts as an OIDC identity provider (IdP). Each workflow run is issued a short-lived, signed JWT describing the run context (repo, branch, workflow, ref).
2. AWS is configured to trust GitHub's IdP via an `aws_iam_openid_connect_provider` resource registered once per account.
3. An IAM role is created with a trust policy that allows assumption only when the OIDC token satisfies specific conditions — scoped to this exact repository (`repo:fejes99/CollabSpace`) and the `main` branch for deployment actions.
4. The workflow calls `aws-actions/configure-aws-credentials` with the role ARN. GitHub exchanges its OIDC token for temporary AWS credentials (valid for 1 hour maximum). No secret is read from GitHub's secret store.

The IAM role's permission policy is scoped to the minimum required: ECR login and image push to the specific repositories created in `infrastructure/shared/`, not a wildcard over the account.

---

## Alternatives Considered

### IAM user with long-lived access keys

The incumbent approach: create an IAM user, generate an access key pair, store both values as GitHub Actions encrypted secrets.

**Rejected** for four reasons:

1. **Leak surface is permanent.** If an access key is exposed — via a log line, a forked-repo PR that prints env vars, a compromised GitHub token, or a future supply-chain compromise in a dependency — the key remains valid until manually rotated. There is no automatic expiry.
2. **Rotation burden.** AWS recommends rotating access keys every 90 days. In practice, long-lived keys are rarely rotated until after an incident. Rotation also requires a coordinated secret update in GitHub.
3. **Blast radius is broad.** A leaked key grants all permissions attached to that IAM user until the key is discovered and disabled. Discovery lag is often measured in hours or days.
4. **Violates CLAUDE.md principle.** The project rule is "no long-lived credentials." OIDC is the correct implementation of that rule for CI/CD.

### AWS CodeBuild instead of GitHub Actions

CodeBuild runs inside AWS, so it can use an IAM execution role directly — no OIDC needed. It eliminates the GitHub ↔ AWS trust setup entirely.

**Rejected.** The project is already using GitHub Actions as the CI/CD platform (established by the monorepo structure and workflow layout). Switching to CodeBuild to avoid OIDC setup would add a new service, new cost (CodeBuild is not always-free), and a more complex debugging surface. OIDC is a one-time setup cost with ongoing benefit. Learning OIDC is also more broadly transferable than learning CodeBuild specifics.

### Self-hosted GitHub Actions runner on EC2

A self-hosted runner inside the VPC could use an EC2 instance profile instead of OIDC. No credentials needed at all.

**Rejected.** Running a persistent EC2 instance solely to host a CI runner is wasteful at this scale and cost target (~$0–5/month). It also introduces a runner maintenance burden. OIDC on GitHub-hosted runners achieves the same security property without a persistent compute cost.

### Storing AWS credentials in GitHub Actions environment secrets (scoped)

A variant of the IAM user approach: scope the secrets to a specific GitHub environment (e.g. `production`) to add an approval gate before they are used.

**Rejected.** This mitigates the misuse risk slightly but does not eliminate the fundamental problems: the credentials are still long-lived, still require rotation, and still have a permanent leak surface if the secret value is ever exposed. OIDC eliminates the credential entirely.

---

## Consequences

**Positive:**

- No AWS credentials stored anywhere in GitHub. Nothing to leak, nothing to rotate, nothing to audit for staleness.
- Credentials issued per workflow run, valid for a maximum of 1 hour. A compromised token from run N cannot be used after run N completes.
- The trust policy condition (`sub` claim scoped to `repo:fejes99/CollabSpace:ref:refs/heads/main`) means no other repository — including forks — can assume the role. Pull request workflows from forks do not receive OIDC tokens with a matching `sub` claim.
- IAM role permissions can be scoped to specific resource ARNs (individual ECR repo ARNs), not account-wide wildcards.
- Audit trail is clean: CloudTrail shows role assumptions with the OIDC subject as context, linking each AWS API call to the specific workflow run that made it.

**Negative:**

- Initial setup is more complex than "copy two secrets into GitHub." The OIDC provider, IAM role, and trust policy condition must all be correct for the first workflow run to succeed. Misconfigured trust conditions produce opaque `AccessDenied` errors that require CloudTrail to diagnose.
- The 1-hour credential window is generous for most pipelines, but an unusually slow build (large multi-platform Docker images, slow test suites) could time out mid-deploy. If this occurs, the fix is to split the pipeline into shorter jobs, each obtaining fresh credentials at the start.
- GitHub's OIDC endpoint (`token.actions.githubusercontent.com`) is an external dependency. If GitHub's token service is degraded, CI pipelines cannot authenticate to AWS regardless of AWS availability. In practice, this dependency already exists — if GitHub is down, the runner itself is unavailable.

---

## Revisit When

- A second AWS account (e.g. `staging`, `production`) is added — the OIDC provider must be registered per account, and separate IAM roles with appropriate permissions should be created per account.
- ECS deployment permissions are needed — the IAM role policy will need to be extended. Review the scope carefully at that point rather than appending `ecs:*`.
- GitHub changes its OIDC token format or claim structure — the trust policy condition uses the `sub` claim; a format change would break authentication silently until a `plan` run hits `AccessDenied`.
