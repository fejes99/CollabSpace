package com.collabspace.authworkspace.domain.model.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record User(UUID id, String name, String email, Optional<String> passwordHash, Instant createdAt,
		Instant updatedAt) {
}
