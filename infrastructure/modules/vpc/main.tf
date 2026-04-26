# ── VPC ────────────────────────────────────────────────────────────────────────

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr

  # Required for ECS tasks to resolve ECR, SSM, and CloudWatch endpoints by
  # hostname (e.g. ecr.eu-central-1.amazonaws.com). Without this, DNS lookups
  # inside the VPC fail and tasks cannot pull images or read config.
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project_name}-${var.environment}-vpc"
  }
}

# ── Internet Gateway ────────────────────────────────────────────────────────────

# The IGW is the single exit point from the VPC to the public internet.
# Without it, public subnets cannot reach ECR, CloudWatch, or SSM.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-${var.environment}-igw"
  }
}

# ── Public subnets ──────────────────────────────────────────────────────────────

# for_each over a list requires converting to a map. zipmap pairs each index
# (used as a stable key) with the AZ name and CIDR for that position.
# count would work too, but for_each gives stable resource addresses if the
# list order changes — "aws_subnet.public[\"0\"]" vs "aws_subnet.public[0]".
resource "aws_subnet" "public" {
  for_each = { for i, az in var.azs : i => {
    az   = az
    cidr = var.public_subnet_cidrs[i]
  } }

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value.cidr
  availability_zone = each.value.az

  # ECS Fargate tasks placed in public subnets must have a public IP to reach
  # the internet via the IGW. The alternative (NAT Gateway) costs ~$32/month.
  # See ADR-009 for the full trade-off analysis.
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project_name}-${var.environment}-public-${each.value.az}"
    Tier = "public"
  }
}

# ── Private subnets ─────────────────────────────────────────────────────────────

# Private subnets have no route to the internet — no IGW, no NAT.
# RDS and Redis live here. ECS tasks reach them via intra-VPC routing,
# which is always free and never leaves the AWS network.
resource "aws_subnet" "private" {
  for_each = { for i, az in var.azs : i => {
    az   = az
    cidr = var.private_subnet_cidrs[i]
  } }

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value.cidr
  availability_zone = each.value.az

  tags = {
    Name = "${var.project_name}-${var.environment}-private-${each.value.az}"
    Tier = "private"
  }
}

# ── Route tables ────────────────────────────────────────────────────────────────

# One route table for all public subnets. The single rule: anything outside
# the VPC (0.0.0.0/0) goes to the IGW. Intra-VPC traffic (10.0.0.0/16) is
# handled by the implicit local route that AWS adds to every route table.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-rt-public"
  }
}

# Attach the public route table to every public subnet.
resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# Private subnets use the VPC's default route table, which only has the
# implicit local route. There is no default internet route — intentional.

# ── S3 VPC Gateway Endpoint ─────────────────────────────────────────────────────

# ECR stores container image layers in S3. When a task pulls an image, the
# first API call goes to ECR (returns image manifest), and then the layer
# downloads come from S3. Without this endpoint, those S3 requests route
# through the public internet and incur data-transfer charges.
#
# Gateway endpoints are free and route matching traffic through AWS's internal
# backbone instead. This is the one VPC endpoint worth adding in any cost-
# sensitive environment.
data "aws_vpc_endpoint_service" "s3" {
  service      = "s3"
  service_type = "Gateway"
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = data.aws_vpc_endpoint_service.s3.service_name
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]

  tags = {
    Name = "${var.project_name}-${var.environment}-vpce-s3"
  }
}
