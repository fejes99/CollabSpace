package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.adapter.in.rest.util.ClientIpResolver;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspaces")
@Tag(name = "Workspaces", description = "Workspace management")
public class WorkspaceController {

	private final CreateWorkspaceUseCase createWorkspaceUseCase;

	public WorkspaceController(CreateWorkspaceUseCase createWorkspaceUseCase) {
		this.createWorkspaceUseCase = createWorkspaceUseCase;
	}

	@Operation(summary = "Create a new workspace",
			description = "Creates a workspace and its first membership (the caller, as admin). Returns a fresh access token whose memberships claim reflects the new workspace, per ADR-032.")
	@ApiResponse(responseCode = "201", description = "Workspace creation successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = CreateWorkspaceResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@PostMapping()
	public ResponseEntity<CreateWorkspaceResponse> createWorkspace(@RequestBody @Valid CreateWorkspaceRequest request,
			HttpServletRequest httpRequest) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			// SecurityConfig's anyRequest().authenticated() guarantees this never fires
			// --
			// if it does, the security config itself is broken, not this request.
			throw new IllegalStateException(
					"Authenticated request reached the controller with no Authentication in the SecurityContext");
		}
		String userId = authentication.getName();
		String ipAddress = ClientIpResolver.resolve(httpRequest);

		CreateWorkspaceCommand command = new CreateWorkspaceCommand(request.name(), request.description(),
				UUID.fromString(userId), Optional.of(ipAddress));

		CreateWorkspaceResult result = createWorkspaceUseCase.create(command);

		return ResponseEntity.status(HttpStatus.CREATED).body(CreateWorkspaceResponse.from(result));
	}

}
