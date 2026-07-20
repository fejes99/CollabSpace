package com.collabspace.authworkspace.application.port.in.workspace.result;

import java.util.UUID;

public record WorkspaceListEntry(UUID id, String name, int memberCount) {
}
