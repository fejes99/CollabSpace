# ── Build stage ──────────────────────────────────────────────────────────────
#
# Creates an isolated venv and installs only the runtime dependencies declared
# in pyproject.toml. tomllib (stdlib since 3.11) extracts the dep list so we
# avoid running `pip install .` (which would try to build and install the app
# package itself, conflicting with serving the app from the source directory).
FROM python:3.14-slim AS build
WORKDIR /build

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY pyproject.toml ./
RUN pip install --no-cache-dir --upgrade pip && \
    python3 -c "\
import tomllib, subprocess, sys; \
deps = tomllib.load(open('pyproject.toml', 'rb'))['project']['dependencies']; \
subprocess.run([sys.executable, '-m', 'pip', 'install', '--no-cache-dir', *deps], check=True)"

# ── Runtime stage ─────────────────────────────────────────────────────────────
#
# Minimal image: venv (runtime deps only) + app source. No pip, no build tools,
# no dev packages. App is served from /app/app/ — not from site-packages — so
# code changes during development don't require a wheel rebuild.
# Non-root user — ECS task role grants AWS permissions; root adds attack surface
# with no benefit. (Same pattern as auth-workspace, document-service, realtime-service.)
FROM python:3.14-slim
WORKDIR /app

RUN addgroup --system appgroup && \
    adduser --system --ingroup appgroup --no-create-home appuser

COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY --chown=appuser:appgroup app/ app/
USER appuser

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
