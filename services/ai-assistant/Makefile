# ai-assistant — service-level Makefile
# Run from services/ai-assistant/. Requires the venv to exist:
#   python3.14 -m venv .venv && source .venv/bin/activate && pip install ".[dev]"

PYTHON := .venv/bin/python
FASTAPI := .venv/bin/fastapi
UVICORN := .venv/bin/uvicorn

.PHONY: dev test lint fmt check help

dev: ## Start dev server with auto-reload (port 8001)
	$(FASTAPI) dev app/main.py --port 8001

test: ## Run tests
	$(PYTHON) -m pytest

lint: ## Check code with ruff
	$(PYTHON) -m ruff check .

fmt: ## Format code with black
	$(PYTHON) -m black .

check: ## Lint + format check (no writes) — same as CI
	$(PYTHON) -m ruff check .
	$(PYTHON) -m black --check .

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
