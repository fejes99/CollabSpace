package com.collabspace.authworkspace.adapter.in.rest.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(@Schema(description = "User ID") UUID id, @Schema(description = "Display name") String name,
		@Schema(description = "Normalised (lowercased) email address") String email,
		@Schema(description = "Registration timestamp in UTC", example = "2026-06-02T10:00:00Z") Instant createdAt) {
}
