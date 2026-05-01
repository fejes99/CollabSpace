# Authorization

Authorization in CollabSpace determines what an authenticated user is permitted to do. It is evaluated inside each downstream service — not at API Gateway — using the workspace membership claims embedded in the JWT. This document describes the RBAC model, how claims map to permissions, where authorization checks live in code, what an authorization failure looks like to the caller, and the distinction between authorization checks and business invariants.

Authentication (who you are) is a separate concern documented in [authentication.md](authentication.md).

---

## Model overview

CollabSpace uses **workspace-scoped role-based access control (RBAC)** with two roles: `admin` and `member`. Both roles are scoped to a specific workspace — a user can be an admin in one workspace and a member in another, and the roles are independent.

There is no global role. A user with no membership in a workspace cannot access any of its resources, regardless of their role in other workspaces.

This model is intentionally simple. Workspace-scoped RBAC with two roles covers every use case described in the v1 product requirements. ABAC (attribute-based access control), per-document permissions, custom roles, and row-level security are explicitly out of scope for v1. → [roadmap.md](../roadmap.md)

---

## Roles and capabilities

| Capability | `admin` | `member` |
|---|:---:|:---:|
| Create workspace | — | — |
| Rename workspace | ✓ | — |
| Delete workspace | ✓ | — |
| Invite member | ✓ | — |
| Remove member | ✓ | — |
| Change member role | ✓ | — |
| View workspace members | ✓ | ✓ |
| Create document | ✓ | ✓ |
| Read document | ✓ | ✓ |
| Update document | ✓ | ✓ |
| Delete document | ✓ | — |
| Ask AI assistant | ✓ | ✓ |

A `member` can perform all document read and write operations but cannot change the workspace structure (membership, name, deletion) or delete documents. The distinction reflects the difference between contributing to collaborative work and administering the workspace itself.

Workspace creation is not role-gated — it is a user-level action. Any authenticated user can create a workspace and becomes its first admin automatically.

---

## How authorization works at runtime

The JWT access token carries the full membership array:

```json
"memberships": [
  { "workspaceId": "ws:01HZ...", "role": "admin" },
  { "workspaceId": "ws:01HX...", "role": "member" }
]
```

API Gateway validates the token's signature, expiry, issuer, and audience. It does not evaluate membership or role. That responsibility belongs to each service.

When a request reaches a service (for example, `DELETE /v1/workspaces/{workspaceId}/documents/{documentId}`), the service extracts the `workspaceId` from the path, finds the matching membership entry in the token claims, and checks whether the user's role permits the requested operation. No database call is required to resolve the role — it is already in the token.

---

## Where authorization checks live in code

**Authorization checks belong at the controller layer**, implemented via Spring Security's `@PreAuthorize` annotation and the `SecurityContext`. The service layer does not contain authorization checks.

The reasoning is separation of concerns: the service layer is responsible for business logic. If authentication and authorization logic also live in the service layer, the service becomes responsible for two different concerns — and those concerns are tested differently (auth is security-tested; business logic is unit-tested). Keeping the controller layer as the authorization boundary also makes it easy to see all protected operations in one place.

A concrete example:

```java
// Controller — authorization boundary
@DeleteMapping("/{workspaceId}/documents/{documentId}")
@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")
public ResponseEntity<Void> deleteDocument(
    @PathVariable String workspaceId,
    @PathVariable String documentId
) {
    documentService.delete(workspaceId, documentId);
    return ResponseEntity.noContent().build();
}

// Service — no authorization check here
public void delete(String workspaceId, String documentId) {
    // business logic only
}
```

The `hasWorkspaceRole` expression is a custom Spring Security method security expression that reads the `memberships` array from the `Authentication` principal and checks whether the current user has the required role in the specified workspace. Its implementation lives in the Auth & Workspace service and is not duplicated in other services.

---

## Business invariants vs authorization

Some constraints look like authorization but are not. These belong in the service layer, not the controller layer, and must be distinguished clearly to avoid placing them in the wrong place.

**Authorization:** "Is this user permitted to perform this action?" This is answered from the JWT claims alone, without querying the database.

**Business invariant:** "Would this action leave the system in an invalid state?" This requires querying the domain model and is enforced by the service layer.

Examples of business invariants that are often confused with authorization:

- **A workspace must always have at least one admin.** If an admin attempts to remove themselves (or another admin) when they are the last admin, the operation must be rejected — not because the user lacks permission (they are an admin), but because the resulting workspace state would be invalid. This check reads the workspace's current admin count from the database.
- **A user cannot remove themselves from a workspace they created.** This is a domain rule that requires knowing which user created the workspace — information that lives in the database, not in the JWT.
- **A document cannot be deleted if it is locked for editing by another user.** This depends on current editing state, not user role.

These invariants throw a domain exception (e.g., `WorkspaceInvariantViolationException`) that maps to a `422 Unprocessable Entity` response at the controller's exception handler. Authorization failures, by contrast, map to `403 Forbidden`. The distinction is meaningful to callers: `403` means "you are not allowed"; `422` means "you are allowed but this specific action is currently impossible."

---

## Authorization failure response

Authorization failures return `403 Forbidden` with an RFC 9457 Problem Details body. The error reveals the minimum information needed to be actionable — it does not expose role details, membership state, or internal identifiers that are not already known to the caller.

```json
{
  "type": "https://errors.collabspace.io/authorization/insufficient-role",
  "title": "Insufficient role",
  "status": 403,
  "detail": "This operation requires the admin role in the requested workspace.",
  "instance": "/v1/workspaces/ws:01HZ.../documents/doc:01HY..."
}
```

There are two categories of authorization failure, and the error response must not conflate them:

- **Not a member of the workspace** (`type: .../not-a-member`). The workspace might not exist from the caller's perspective, or the user has no membership. Return `403` in both cases — revealing whether the workspace exists to a non-member is an information leak.
- **Insufficient role** (`type: .../insufficient-role`). The user is a member but their role does not permit the operation. Return `403` with a description of the required role.

Both cases use `403`, not `404`. Returning `404` for "workspace exists but you are not a member" would leak workspace existence. This is the same principle as not revealing whether an email address is registered at login.

---

## Multi-service authorization

The Auth & Workspace service is the only service that manages memberships and roles. The other services (Document Service, AI Assistant) receive the JWT via API Gateway and read the claims to make authorization decisions. They do not call the Auth service to verify roles — the token is the authorization proof.

This means every service that protects resources behind workspace-level access must implement the same claims-reading logic. This is intentional duplication at the code level but not at the data level: the role data has one source of truth (the Auth service database), and it is propagated via the token. The implementation of the claims-reading logic is small enough (~20 lines) that it is reasonable to duplicate rather than extract into a shared library.

Service-to-service calls (currently: AI Assistant → Document Service) use a separate service identity token rather than a user JWT. That token does not carry `memberships` claims. Services receiving a service identity token must not apply workspace RBAC — the caller is a trusted internal service, not a user. → [ADR-014](../06-decisions/adr-014-service-to-service-auth.md)

---

## Out of scope

- **ABAC.** Per-document permissions, conditional access based on document attributes, or field-level authorization are not in v1.
- **Custom roles.** The two-role model covers all v1 use cases.
- **Cross-workspace permissions.** There is no concept of a user having elevated access across all workspaces. There is no superadmin.
- **Resource ownership.** Documents are workspace-owned, not user-owned. A document's creator has no special permissions beyond their workspace role.
