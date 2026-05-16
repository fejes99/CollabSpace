# ADR-025 — Hexagonal Architecture for auth-workspace

## Status

Accepted

## Date

2026-05-16

## Context

auth-workspace needs a package structure before service implementation begins. The walking skeleton has no structure beyond a single application class. As endpoints, persistence, Redis, and SSM integrations are added across multiple PRs, the structure chosen now determines how easy it is to test each component in isolation, how clearly boundaries are enforced, and what habits the implementation teaches.

The project is explicitly a learning environment — the structure chosen should reflect professional patterns found in production Java codebases, not just "what is fastest to set up."

Three failure modes to avoid:

1. **Framework leakage into domain logic** — business rules that import `HttpServletRequest`, `@Entity`, or `RedisTemplate` are impossible to unit-test without a running application context.
2. **Untestable service layer** — a `UserService` that directly calls a JPA repository can only be tested with a database. An interface-based dependency can be swapped for a test double.
3. **Implicit coupling** — when controllers reach directly into repositories, the only way to find all callers of a data operation is to grep the whole codebase.

## Decision

Use **Hexagonal Architecture (Ports and Adapters)** for auth-workspace.

The root package is `com.collabspace.authworkspace`. Structure:

```
com.collabspace.authworkspace/
│
├── domain/
│   ├── model/              Pure Java records/classes. No Spring, JPA, or framework annotations.
│   └── exception/          Domain exceptions thrown by application services.
│
├── application/
│   ├── port/
│   │   ├── in/             Inbound use case interfaces (one per operation).
│   │   └── out/            Outbound port interfaces (repository and external service contracts).
│   └── service/            Application services: implement in-ports, depend on out-ports only.
│
└── adapter/
    ├── in/
    │   └── rest/           REST controllers and request/response DTOs.
    └── out/
        ├── persistence/    JPA entities and Spring Data implementations of out-ports.
        ├── redis/          Redis adapter implementing blocklist and session out-ports.
        └── ssm/            SSM Parameter Store adapter for config loaded at startup.
```

`rest/` is chosen over `web/` because this service exposes HTTP REST endpoints only. `web/` (used by Hombergs in the reference implementation) is a general-purpose name intended for any HTTP adapter. For a service with a single known protocol, naming the folder after the concrete technology (`rest/`) is more precise and self-documenting. If a WebSocket adapter were ever needed, it would get its own sibling folder (`websocket/`).

**Dependency rule:** dependencies point inward only.
```
adapter/in/rest  →  application/port/in  →  application/service  →  application/port/out  →  adapter/out/*
```
Nothing in `domain/` or `application/` imports from `adapter/`. The compiler enforces this if packages are kept clean — no framework type should appear in `domain/` or `application/port/`.

## Alternatives considered

**Package by layer** (`controller/`, `service/`, `repository/`, `model/`)
Rejected. Provides no enforcement of boundaries — a controller can import a repository directly and the compiler won't object. Teaches a pattern the developer has already seen and explicitly wants to move past.

**Package by feature** (`auth/`, `workspace/`, `membership/`)
Rejected. auth-workspace is a single bounded context; its features share infrastructure (the same database, the same JWT signing key, the same `UserRepository`). Package-by-feature would force arbitrary splits of shared components without enforcing any dependency rules.

**Vertical Slice Architecture**
Rejected. Each slice owns its own persistence with no shared service layer. This conflicts with shared components like `JwtService` and `UserRepository` that multiple use cases require. Vertical slices benefit large teams with clearly independent features; at this service's scale the indirection adds cost with no coordination benefit.

## Consequences

\+ Domain model is testable with plain JUnit — no Spring context, no database, no mocks of framework types.

\+ Out-port interfaces (`UserRepository`, `TokenBlocklistPort`) are what tests stub, not JPA or Redis types directly. Tests stay fast and deterministic.

\+ Adding or replacing a persistence backend changes only `adapter/out/persistence`. Application and domain code is untouched.

\+ Every layer has a single, explicit dependency direction. Where a component lives determines what it is allowed to import.

− More files than package-by-layer. A single "register user" flow touches: controller → use case interface → application service → repository interface → JPA adapter. Five files where package-by-layer would use three.

− Mapping between adapter DTOs and domain models requires explicit conversion code. Records reduce boilerplate but the conversion is still manual per operation.

− New contributors need to understand the pattern before the structure makes sense. Orientation cost is higher than for flat layering.

## Revisit when

- A second bounded context is added to auth-workspace with genuinely independent persistence (unlikely given the service charter in ADR-002).
- The team adopts vertical slice ownership per feature team.
- GraalVM native image compilation constraints conflict with the reflection patterns this layout relies on.
