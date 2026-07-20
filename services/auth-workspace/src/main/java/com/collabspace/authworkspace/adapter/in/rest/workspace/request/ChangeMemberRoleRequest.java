package com.collabspace.authworkspace.adapter.in.rest.workspace.request;

import com.collabspace.authworkspace.adapter.in.rest.workspace.validation.ValidRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ChangeMemberRoleRequest(
		@Schema(description = "Member role", example = "member") @NotBlank @ValidRole String role) {
}
