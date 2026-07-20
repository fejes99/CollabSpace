package com.collabspace.authworkspace.adapter.in.rest.workspace.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
		@Schema(description = "Workspace name", example = "Engineering") @NotBlank @Size(max = 255) String name,
		@Schema(description = "Workspace description",
				example = "Engineering workspace containing engineering documents") @Size(
						max = 2000) String description) {
}
