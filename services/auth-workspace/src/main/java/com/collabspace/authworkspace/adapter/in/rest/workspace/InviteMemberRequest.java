package com.collabspace.authworkspace.adapter.in.rest.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteMemberRequest(
		@Schema(description = "Member email address",
				example = "patrice@example.com") @NotBlank @Email @Size(max = 254) String email,
		@Schema(description = "Member role", example = "member") @ValidRole String role) {
}
