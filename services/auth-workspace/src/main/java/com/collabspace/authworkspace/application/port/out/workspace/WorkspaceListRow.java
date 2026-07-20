package com.collabspace.authworkspace.application.port.out.workspace;

import java.time.Instant;
import java.util.UUID;

// memberCount is `long`, not `int`, because the JPQL COUNT() this projects from is
// typed Long -- Hibernate's SELECT NEW constructor matching doesn't implicitly narrow
// Long to int, so a mismatched primitive here fails at query execution, not compile
// time. Narrow to int at the point of use instead (WorkspaceApplicationService).
public record WorkspaceListRow(UUID id, String name, Instant createdAt, long memberCount) {
}
