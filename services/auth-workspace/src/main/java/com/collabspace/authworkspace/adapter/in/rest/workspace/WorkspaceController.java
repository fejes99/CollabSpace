package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.adapter.in.rest.util.ClientIpResolver;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberResult;
import com.collabspace.authworkspace.application.port.in.workspace.InviteMemberUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
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

	private final InviteMemberUseCase inviteMemberUseCase;

	public WorkspaceController(CreateWorkspaceUseCase createWorkspaceUseCase, InviteMemberUseCase inviteMemberUseCase) {
		this.createWorkspaceUseCase = createWorkspaceUseCase;
		this.inviteMemberUseCase = inviteMemberUseCase;
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
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping()
	public ResponseEntity<CreateWorkspaceResponse> createWorkspace(@RequestBody @Valid CreateWorkspaceRequest request,
			HttpServletRequest httpRequest) {
		UUID userId = currentUserId();
		String ipAddress = ClientIpResolver.resolve(httpRequest);

		CreateWorkspaceCommand command = new CreateWorkspaceCommand(request.name(), request.description(), userId,
				Optional.of(ipAddress));

		CreateWorkspaceResult result = createWorkspaceUseCase.create(command);

		return ResponseEntity.status(HttpStatus.CREATED).body(CreateWorkspaceResponse.from(result));
	}

	@Operation(summary = "Invite member to workspace",
			description = "Invites member to workspace as 'member' or 'admin' role. Returns a new record of user information and workspace information")
	@ApiResponse(responseCode = "201", description = "Member invitation successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = InviteMemberResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "403",
			description = "Caller is not a member of the workspace, or is a member without the admin role",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "404", description = "No registered user matches the invited email address",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "409", description = "User already a member of workspace",
			content = @Content(mediaType = "application/problem+json"))
	@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")
	@PostMapping("/{workspaceId}/members")
	public ResponseEntity<InviteMemberResponse> inviteMember(@PathVariable UUID workspaceId,
			@RequestBody @Valid InviteMemberRequest request, HttpServletRequest httpRequest) {
		UUID adminId = currentUserId();
		String ipAddress = ClientIpResolver.resolve(httpRequest);
		String correlationId = MDC.get("correlationId");

		InviteMemberCommand command = new InviteMemberCommand(adminId, workspaceId, request.email(),
				Optional.ofNullable(request.role()), Optional.ofNullable(correlationId), Optional.of(ipAddress));

		InviteMemberResult result = inviteMemberUseCase.invite(command);

		return ResponseEntity.status(HttpStatus.CREATED).body(InviteMemberResponse.from(result));
	}

	private static UUID currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			// SecurityConfig's anyRequest().authenticated() guarantees this never fires
			// --
			// if it does, the security config itself is broken, not this request.
			throw new IllegalStateException(
					"Authenticated request reached the controller with no Authentication in the SecurityContext");
		}
		return UUID.fromString(authentication.getName());
	}

}
