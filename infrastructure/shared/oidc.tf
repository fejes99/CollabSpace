# ── GitHub OIDC identity provider ─────────────────────────────────────────────

resource "aws_iam_openid_connect_provider" "github_actions" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = ["sts.amazonaws.com"]

  # AWS validates GitHub tokens via its CA store, not this thumbprint.
  # The field is still required by the resource schema.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = {
    Name        = "${var.project_name}-github-oidc-provider"
    Environment = "global"
    Service     = "iam"
    ManagedBy   = "terraform"
  }
}

# ── CI IAM role ────────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "github_actions_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github_actions.arn]
    }

    # Audience must match the client_id_list above.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Scope to any ref in this specific repository.
    # StringLike with :* allows all branches and tags.
    # Tighten to :ref:refs/heads/main when adding deploy permissions.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_org}/${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions_ci" {
  name               = "${var.project_name}-github-actions-ci"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json

  tags = {
    Name        = "${var.project_name}-github-actions-ci"
    Environment = "global"
    Service     = "iam"
    ManagedBy   = "terraform"
  }
}

# ── ECR push policy ────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "ecr_push" {
  # GetAuthorizationToken is account-scoped — resource ARNs are not supported.
  statement {
    sid       = "ECRAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # All other ECR actions are scoped to only the repositories created above.
  statement {
    sid    = "ECRPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = [for repo in aws_ecr_repository.services : repo.arn]
  }
}

resource "aws_iam_policy" "ecr_push" {
  name        = "${var.project_name}-ecr-push"
  description = "Allows GitHub Actions CI to push images to CollabSpace ECR repositories."
  policy      = data.aws_iam_policy_document.ecr_push.json

  tags = {
    Name        = "${var.project_name}-ecr-push"
    Environment = "global"
    Service     = "iam"
    ManagedBy   = "terraform"
  }
}

resource "aws_iam_role_policy_attachment" "github_actions_ecr_push" {
  role       = aws_iam_role.github_actions_ci.name
  policy_arn = aws_iam_policy.ecr_push.arn
}
