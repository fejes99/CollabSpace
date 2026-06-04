# CollabSpace — repository root Makefile
#
# Two groups of targets:
#   LOCAL DEV  — Docker Compose (postgres, mongo, redis, localstack)
#   AWS DEV    — dev environment lifecycle (terraform + ECS scale)
#
# Run `make` or `make help` to list all targets.

.PHONY: up down reset up-all down-all setup-local logs auth-swagger \
        dev-plan dev-up dev-down dev-pause dev-resume dev-status \
        dev-start dev-stop \
        help

# ── Configuration ─────────────────────────────────────────────────────────────

LOCALSTACK_ENDPOINT := http://localhost:4566
LOCALSTACK_PROFILE  := localstack
AWS_LOCAL           := aws --endpoint-url $(LOCALSTACK_ENDPOINT) --profile $(LOCALSTACK_PROFILE)

DEV_DIR     := infrastructure/environments/dev
DEV_CLUSTER := collabspace-dev
DEV_REGION  := eu-central-1

# Add a service name here each time a walking skeleton is wired into ECS.
# Name must match the service_name variable passed to the ecs-service module.
# See ADR-022 for the lifecycle strategy these targets implement.
DEV_SERVICES := \
	auth-workspace \
	document-service \
	realtime-service \
	ai-assistant
# TODO(Stage 2): add a dev-ai target (native uvicorn run + pgvector DB) once the
# database dependency is wired. See services/ai-assistant/README.md.

# ── Local infrastructure (Docker Compose) ────────────────────────────────────

up: ## Start infrastructure containers (postgres, mongo, redis, localstack)
	docker compose up -d

down: ## Stop and remove infrastructure containers (volumes preserved)
	docker compose down

reset: ## Stop, wipe volumes, restart, and re-provision LocalStack resources
	docker compose down -v
	$(MAKE) up
	$(MAKE) setup-local

up-all: ## Start infrastructure + application containers (--profile services)
	docker compose --profile services up -d

down-all: ## Stop all containers including application services
	docker compose --profile services down

setup-local: ## Provision LocalStack resources — idempotent, safe to re-run
	@echo "==> Creating SNS topics..."
	@$(AWS_LOCAL) sns create-topic --name document-events --region $(DEV_REGION) > /dev/null
	@echo "==> Creating SQS queues..."
	@$(AWS_LOCAL) sqs create-queue --queue-name notifications-dlq --region $(DEV_REGION) > /dev/null
	@$(AWS_LOCAL) sqs create-queue --queue-name notifications \
		--attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:$(DEV_REGION):000000000000:notifications-dlq\",\"maxReceiveCount\":\"5\"}"}' \
		--region $(DEV_REGION) > /dev/null
	@$(AWS_LOCAL) sqs create-queue --queue-name realtime-updates-dlq --region $(DEV_REGION) > /dev/null
	@$(AWS_LOCAL) sqs create-queue --queue-name realtime-updates \
		--attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:$(DEV_REGION):000000000000:realtime-updates-dlq\",\"maxReceiveCount\":\"5\"}"}' \
		--region $(DEV_REGION) > /dev/null
	@echo "==> Subscribing queues to SNS topic..."
	@$(AWS_LOCAL) sns subscribe \
		--topic-arn arn:aws:sns:$(DEV_REGION):000000000000:document-events \
		--protocol sqs \
		--notification-endpoint arn:aws:sqs:$(DEV_REGION):000000000000:notifications \
		--region $(DEV_REGION) > /dev/null
	@$(AWS_LOCAL) sns subscribe \
		--topic-arn arn:aws:sns:$(DEV_REGION):000000000000:document-events \
		--protocol sqs \
		--notification-endpoint arn:aws:sqs:$(DEV_REGION):000000000000:realtime-updates \
		--region $(DEV_REGION) > /dev/null
	@echo "==> LocalStack resources ready."

logs: ## Tail docker compose logs; filter with: make logs s=postgres
	docker compose logs -f $(s)

auth-swagger: up ## Build + start auth-workspace in Docker, open Swagger UI when healthy
	docker compose --profile services up -d --build auth-workspace
	@echo -n "==> Waiting for auth-workspace"
	@until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do \
		printf '.'; sleep 2; done
	@echo " ready"
	@open http://localhost:8080/swagger-ui.html

# ── AWS dev environment lifecycle ─────────────────────────────────────────────
# See docs/06-decisions/adr-022-dev-environment-lifecycle.md

dev-plan: ## Preview AWS infrastructure changes without applying (terraform plan)
	cd $(DEV_DIR) && terraform plan

dev-up: ## Bring up the AWS dev environment — interactive, confirms before applying
	cd $(DEV_DIR) && terraform apply

dev-down: ## Tear down the AWS dev environment to $0 — interactive, confirms before destroying
	cd $(DEV_DIR) && terraform destroy

dev-pause: ## Scale all ECS services to 0 — stops Fargate billing; ALB still runs (~$$0.022/hr)
	@for svc in $(DEV_SERVICES); do \
		echo "==> Pausing $$svc..."; \
		aws ecs update-service \
			--cluster $(DEV_CLUSTER) \
			--service $(DEV_CLUSTER)-$$svc \
			--desired-count 0 \
			--region $(DEV_REGION) \
			--no-cli-pager > /dev/null; \
	done
	@echo "==> All services paused. Run 'make dev-status' to confirm."

dev-resume: ## Scale all ECS services back to 1 — tasks start in ~30 seconds
	@for svc in $(DEV_SERVICES); do \
		echo "==> Resuming $$svc..."; \
		aws ecs update-service \
			--cluster $(DEV_CLUSTER) \
			--service $(DEV_CLUSTER)-$$svc \
			--desired-count 1 \
			--region $(DEV_REGION) \
			--no-cli-pager > /dev/null; \
	done
	@echo "==> Services resuming — allow ~30 seconds, then run 'make dev-status'."

dev-status: ## Show running/desired task counts for all ECS services
	@for svc in $(DEV_SERVICES); do \
		aws ecs describe-services \
			--cluster $(DEV_CLUSTER) \
			--services $(DEV_CLUSTER)-$$svc \
			--region $(DEV_REGION) \
			--no-cli-pager \
			--query 'services[0].{Service:serviceName,Running:runningCount,Desired:desiredCount,Status:status}' \
			--output table; \
	done

dev-start: ## Start a single ECS service — make dev-start s=auth-workspace
	@test -n "$(s)" || (echo "ERROR: specify a service: make dev-start s=<service-name>" && exit 1)
	aws ecs update-service \
		--cluster $(DEV_CLUSTER) \
		--service $(DEV_CLUSTER)-$(s) \
		--desired-count 1 \
		--region $(DEV_REGION) \
		--no-cli-pager > /dev/null
	@echo "==> $(s) starting. Run 'make dev-status' to confirm."

dev-stop: ## Stop a single ECS service — make dev-stop s=auth-workspace
	@test -n "$(s)" || (echo "ERROR: specify a service: make dev-stop s=<service-name>" && exit 1)
	aws ecs update-service \
		--cluster $(DEV_CLUSTER) \
		--service $(DEV_CLUSTER)-$(s) \
		--desired-count 0 \
		--region $(DEV_REGION) \
		--no-cli-pager > /dev/null
	@echo "==> $(s) stopped."

# ── Help ──────────────────────────────────────────────────────────────────────

help: ## Show available targets
	@echo ""
	@echo "Local dev (Docker Compose):"
	@grep -E '^(up|down|reset|up-all|down-all|setup-local|logs|auth-swagger):.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'
	@echo ""
	@echo "AWS dev environment:"
	@grep -E '^dev-.*:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'
	@echo ""

.DEFAULT_GOAL := help
