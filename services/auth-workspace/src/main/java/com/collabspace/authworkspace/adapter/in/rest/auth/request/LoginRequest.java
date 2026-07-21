package com.collabspace.authworkspace.adapter.in.rest.auth.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@Schema(description = "Email address",
				example = "alice@example.com") @NotBlank @Email @Size(max = 254) String email,
		@Schema(description = "Password — max 128 characters",
				example = "s3curepassword") @NotBlank @Size(max = 128) String password) {
}
