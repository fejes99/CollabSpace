package com.collabspace.authworkspace.application.port.in.auth.command;

import java.util.Optional;
import java.util.UUID;

public record LogoutCommand(UUID userId, String jti, long iat, Optional<String> refreshToken) {
}
