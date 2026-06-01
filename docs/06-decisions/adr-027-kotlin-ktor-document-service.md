# ADR-027: Kotlin + Ktor for Document Service

**Status:** Accepted
**Date:** 2026-06-01
**Supersedes:** ADR-017 (Fastify over Express) — for `document-service` only. ADR-017 remains in force for `realtime-service` and `notification`.

---

## Context

The Document Service is a walking skeleton — it has a health endpoint and nothing else. The original stack is TypeScript + Node 24 + Fastify, chosen in ADR-017 for its JSON-native I/O model and MongoDB ecosystem fit.

Two things changed since ADR-017:

1. **gRPC server requirement.** The Document Service will host a gRPC server (ADR-028) consumed by the AI Assistant for document indexing. `grpc-kotlin` provides coroutine-native gRPC stubs that integrate directly with Ktor's suspend function model. A TypeScript gRPC implementation (`grpc-js`) is possible but requires more ceremony — stubs are callback-based by default and must be promisified manually. Kotlin + Ktor was the decisive factor for the transport choice.

2. **Learning breadth.** The project's current stack covers Java/Spring (auth-workspace) and TypeScript/Fastify (realtime-service, notification). Adding Kotlin/Ktor introduces: a third runtime (JVM with structured concurrency via coroutines, distinct from Java virtual threads), a different DI model (Koin's explicit module DSL vs Spring's annotation scanning), and Kotlin's first-class null safety and sealed class exhaustiveness. None of this is achievable by staying with TypeScript.

The walking skeleton status means the throwaway cost of migrating now is near-zero — there is no business logic to rewrite.

---

## Decision

Migrate the Document Service from TypeScript + Node 24 + Fastify to **Kotlin + Ktor**.

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Coroutines; sealed classes; null safety without nullability annotations |
| HTTP framework | Ktor | Plugin system; no classpath scanning; different architecture from Spring |
| Build tool | Gradle KTS | Idiomatic for Kotlin; required for `protobuf` plugin (ADR-028 codegen) |
| DI | Koin | Native Ktor plugin; module DSL is fundamentally different from Spring |
| Serialization | kotlinx.serialization | Integrated with Ktor; `@Serializable` on data classes |
| Database driver | MongoDB Kotlin coroutine driver | Coroutine-native; no `runBlocking` wrapper |
| Architecture | Feature-based modular | Ktor plugin system organises cross-cutting concerns naturally |
| Error model | Per-operation sealed classes | Exhaustive `when`; per §Error model below |

### Package structure

```
src/main/kotlin/com/collabspace/documentservice/
├── Application.kt                   — main(); installs all Ktor plugins and Koin modules
├── plugins/
│   ├── Routing.kt                   — installs all route extension functions
│   ├── Serialization.kt             — kotlinx.serialization config
│   ├── StatusPages.kt               — maps DocumentServiceError subtypes → HTTP responses
│   └── DI.kt                        — all Koin module definitions
├── document/
│   ├── DocumentRoutes.kt            — Ktor route handlers (thin; delegate to service)
│   ├── DocumentService.kt           — business logic; returns sealed result types
│   ├── DocumentRepository.kt        — MongoDB operations via coroutine driver
│   └── model/
│       ├── Document.kt              — domain data class
│       ├── DocumentRequest.kt       — request DTO (@Serializable)
│       └── DocumentResponse.kt      — response DTO (@Serializable)
└── grpc/
    ├── DocumentGrpcService.kt       — suspend gRPC handler (see ADR-028)
    └── GrpcServer.kt                — gRPC server lifecycle on port 9090
```

### Error model

Each service operation returns a per-operation sealed class. A shared `interface DocumentServiceError` allows `StatusPages.kt` to map any error to HTTP without coupling to individual operations:

```kotlin
interface DocumentServiceError

sealed class GetDocumentResult {
    data class Found(val document: Document) : GetDocumentResult()
    data class NotFound(val id: String) : GetDocumentResult(), DocumentServiceError
}

sealed class CreateDocumentResult {
    data class Created(val document: Document) : CreateDocumentResult()
    data class WorkspaceNotFound(val workspaceId: String) : CreateDocumentResult(), DocumentServiceError
    data class Conflict(val title: String) : CreateDocumentResult(), DocumentServiceError
}
```

The `when` expression in each route handler is exhaustive over exactly the outcomes that can happen for that operation. Adding a new variant is a compile error until all call sites handle it. `!!` anywhere in the codebase is treated as a bug in code review.

---

## Rationale

### Why Ktor over Spring Boot

ADR-002 chose Spring Boot for auth-workspace because Spring Security's JWT handling and Bean Validation reduce the surface area for auth bugs — the area most likely to cause harm in a learning project. Those reasons don't apply to the Document Service. The Document Service has no auth logic of its own: it validates the internal service JWT from the AI Assistant (one interceptor, ADR-028) and trusts the `X-User-Id` / `X-User-Workspaces` headers injected by API Gateway.

Ktor is a different mental model from Spring: no annotation magic, no classpath scanning, explicit plugin installation. Learning both side by side — in two services that live in the same repository — is the point.

### Why Koin over manual DI

Manual DI in `Application.kt` is simple for small services but becomes a wall of constructor calls as features are added. Koin adds minimal overhead while teaching a DI model that is fundamentally different from Spring: modules are lambdas, not annotations; the DI graph is assembled at runtime from explicit declarations; injection is `inject()`, not `@Autowired`. The contrast with Spring is instructive for understanding what DI frameworks actually do.

### Why feature-based packaging, not hexagonal

Hexagonal architecture (ADR-025) is the right choice for auth-workspace because the Auth Service has complex domain rules (token lifecycle, blocklist, workspace RBAC) that benefit from the inward-dependency rule enforced structurally. The Document Service at v1 is a thin CRUD layer over MongoDB. Feature-based packaging with explicit service and repository layers is proportionate, Ktor-idiomatic, and avoids indirection that adds no value at this scale. If the domain logic grows to justify hexagonal boundaries, they can be introduced incrementally.

### Why now

The Document Service is a walking skeleton. Migrating at this point costs nothing. After user registration is implemented in auth-workspace and real feature work begins on the Document Service, a migration would require rewriting business logic and has higher risk.

---

## Consequences

**Positive:**
- Coroutine model (`suspend` functions) integrates directly with `grpc-kotlin` — no thread-per-call or callback wrapping.
- Kotlin's sealed classes + exhaustive `when` enforce correct error handling at compile time.
- Null safety eliminates a class of runtime errors present in TypeScript's `undefined`/`null`.
- Gradle KTS enables the `com.google.protobuf` plugin needed for gRPC code generation (ADR-028).
- Third JVM runtime in the project (coroutines vs virtual threads) achieves the learning breadth goal.

**Negative:**
- JVM startup time is higher than Node.js (~5–10 seconds vs ~1–2 seconds). Affects `make dev-start` wait time but is acceptable at development pace.
- Maintaining four languages (Java, Kotlin, TypeScript, Python) increases context-switching overhead. Mitigated by Java/Kotlin sharing the JVM and IntelliJ tooling.
- `grpc-kotlin` codegen requires the `protoc` binary and Gradle plugin configuration — one-time setup cost.

---

## Rejected alternatives

**Kotlin + Spring Boot**
Spring Boot supports Kotlin well and Kotlin coroutines integrate with Spring's reactive model. Rejected because auth-workspace already covers Spring; running two Spring Boot services is less instructive than one each of Spring and Ktor. The learning breadth goal explicitly requires a second JVM framework.

**Keep TypeScript + Fastify**
Viable, but loses the `grpc-kotlin` integration advantage and doesn't advance the learning breadth goal. The walking skeleton status means there is no cost to switching now.

**Scala + Play or http4s**
Scala on the JVM would also provide functional programming patterns. Rejected as out of scope for this project's learning goals — Kotlin is already a significant addition and Scala's learning curve is steeper.
