# ── Build stage ───────────────────────────────────────────────────────────────
#
# Install all deps (including dev) and compile TypeScript. Only the dist/
# output is carried into the runtime stage — no source files, no dev tooling.
FROM node:24-alpine AS build
WORKDIR /build

COPY package.json pnpm-lock.yaml ./
RUN corepack enable pnpm && pnpm install --frozen-lockfile --ignore-scripts

COPY tsconfig.json ./
COPY src/ src/
RUN pnpm run build

# ── Runtime stage ─────────────────────────────────────────────────────────────
#
# Production deps only + compiled output. Keeps the image small and reduces
# the attack surface by excluding the TypeScript compiler and dev packages.
FROM node:24-alpine
WORKDIR /app

# Non-root user — the ECS task role grants AWS permissions; root adds attack
# surface with no benefit. (Same pattern as auth-workspace.)
RUN addgroup --system appgroup && \
    adduser --system --ingroup appgroup --no-create-home appuser

COPY package.json pnpm-lock.yaml ./
RUN corepack enable pnpm && pnpm install --frozen-lockfile --prod --ignore-scripts

COPY --from=build /build/dist dist/
RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 3000

CMD ["node", "dist/server.js"]
