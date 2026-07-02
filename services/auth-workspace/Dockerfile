# ── Build stage ───────────────────────────────────────────────────────────────
#
# Copy the dependency manifest first so Docker can cache the downloaded
# dependencies as a separate layer. Re-running mvn dependency:go-offline only
# happens when pom.xml or the wrapper config changes, not on every source edit.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
#
# JRE-only image: removes the compiler, jshell, and other JDK tools that have
# no role at runtime, reducing the attack surface and image size.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Non-root user. Containers should not run as root unless there is a specific
# reason — the ECS task role already grants AWS permissions, so root adds risk
# with no benefit.
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

COPY --from=build /build/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
