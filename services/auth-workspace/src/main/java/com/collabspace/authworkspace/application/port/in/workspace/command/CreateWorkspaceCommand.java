package com.collabspace.authworkspace.application.port.in.workspace.command;

import java.util.Optional;
import java.util.UUID;

public record CreateWorkspaceCommand(String name, String description, UUID userId, Optional<String> ipAddress) {

}
