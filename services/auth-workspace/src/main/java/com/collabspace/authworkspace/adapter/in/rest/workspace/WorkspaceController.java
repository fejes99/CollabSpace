package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workspaces")
@Tag(name = "Workspaces", description = "Workspace management")
public class WorkspaceController {

	private final CreateWorkspaceUseCase createWorkspaceUseCase;

	public WorkspaceController(CreateWorkspaceUseCase createWorkspaceUseCase) {
		this.createWorkspaceUseCase = createWorkspaceUseCase;
	}

	@ApiResponse(responseCode = "201", description = "Workspace creation successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = CreateWorkspaceResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@PostMapping()
	public ResponseEntity<CreateWorkspaceResponse> createWorkspace(
			@RequestBody @Valid CreateWorkspaceRequest createWorkspaceRequest) {
		// Extract ip address
		// Create CreateWorkspaceCommand
		// Call CreateWorkspaceUseCase
		// Return response
		throw new UnsupportedOperationException("Not supported yet.");
	}

}
