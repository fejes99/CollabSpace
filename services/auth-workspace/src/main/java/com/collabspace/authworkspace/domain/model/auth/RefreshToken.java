package com.collabspace.authworkspace.domain.model.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record RefreshToken(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt,
		Optional<String> userAgent, Optional<String> ipAddress) {

}
