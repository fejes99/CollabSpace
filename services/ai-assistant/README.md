# ai-assistant

AI assistant service for CollabSpace — document search, embeddings, and LLM-powered Q&A.

**Stack:** Python 3.14 · FastAPI · PostgreSQL + pgvector (Stage 2+) · Kafka consumer (Stage 2+)

**Current stage:** Walking skeleton — health endpoint only.

## Endpoints

| Method | Path      | Description                                 |
| ------ | --------- | ------------------------------------------- |
| `GET`  | `/health` | Health check — returns `{ "status": "ok" }` |

## Running locally

```bash
python -m venv .venv
source .venv/bin/activate        # macOS/Linux
pip install ".[dev]"
cp .env.example .env             # adjust values as needed
uvicorn app.main:app --reload --port 8001
```

## Environment variables

| Variable      | Default       | Description                                        |
| ------------- | ------------- | -------------------------------------------------- |
| `PORT`        | `8001`        | TCP port to listen on (read by config; see note)   |
| `LOG_LEVEL`   | `info`        | structlog level: `debug` `info` `warning` `error`  |
| `ENVIRONMENT` | `development` | Runtime environment name                           |

All variables are validated at startup via pydantic-settings — the process exits with a clear error if a variable is present but malformed.

> **Note:** `PORT` is declared in `app/config.py` for future use. The current Dockerfile
> CMD and local `uvicorn` command hardcode `--port 8001`. Wiring `PORT` into the uvicorn
> entrypoint is a Stage 2 task tracked in the Makefile comment below.

## Tests

```bash
pytest              # runs tests/
ruff check .        # lint
black --check .     # format check
```

## Building for production

```bash
docker build -t ai-assistant .
docker run -p 8001:8001 ai-assistant
```

## Project structure

```
app/
  __init__.py
  config.py    — pydantic-settings env validation; exits on bad config
  main.py      — FastAPI app instance, /health route, structlog setup
tests/
  __init__.py
  test_health.py
pyproject.toml — runtime deps + ruff/black/pytest tool config
Dockerfile     — multi-stage: python:3.14-slim builder + runtime
```

## Stage 2: Local development with Docker Compose

A `make dev-ai` target will be added to the root `Makefile` once the PostgreSQL + pgvector
database dependency is wired. Until then, run the service natively with `uvicorn` as shown above.
The placeholder comment is already in the root `Makefile` under `DEV_SERVICES`.
