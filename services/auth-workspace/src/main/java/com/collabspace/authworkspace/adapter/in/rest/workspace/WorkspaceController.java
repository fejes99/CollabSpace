package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.adapter.in.rest.common.PagedResponse;
import com.collabspace.authworkspace.adapter.in.rest.common.PaginationMetadata;
import com.collabspace.authworkspace.adapter.in.rest.util.ClientIpResolver;
import com.collabspace.authworkspace.adapter.in.rest.workspace.request.ChangeMemberRoleRequest;
import com.collabspace.authworkspace.adapter.in.rest.workspace.request.CreateWorkspaceRequest;
import com.collabspace.authworkspace.adapter.in.rest.workspace.request.InviteMemberRequest;
import com.collabspace.authworkspace.adapter.in.rest.workspace.response.ChangeMemberRoleResponse;
import com.collabspace.authworkspace.adapter.in.rest.workspace.response.CreateWorkspaceResponse;
import com.collabspace.authworkspace.adapter.in.rest.workspace.response.InviteMemberResponse;
import com.collabspace.authworkspace.adapter.in.rest.workspace.response.WorkspaceListItem;
import com.collabspace.authworkspace.adapter.in.rest.workspace.validation.ValidAfter;
import com.collabspace.authworkspace.application.port.in.workspace.command.ChangeMemberRoleCommand;
import com.collabspace.authworkspace.application.port.in.workspace.command.CreateWorkspaceCommand;
import com.collabspace.authworkspace.application.port.in.workspace.command.InviteMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.command.ListWorkspacesCommand;
import com.collabspace.authworkspace.application.port.in.workspace.command.RemoveMemberCommand;
import com.collabspace.authworkspace.application.port.in.workspace.result.ChangeMemberRoleResult;
import com.collabspace.authworkspace.application.port.in.workspace.result.CreateWorkspaceResult;
import com.collabspace.authworkspace.application.port.in.workspace.result.InviteMemberResult;
import com.collabspace.authworkspace.application.port.in.workspace.result.ListWorkspacesResult;
import com.collabspace.authworkspace.application.port.in.workspace.usecase.ChangeMemberRoleUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.usecase.CreateWorkspaceUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.usecase.InviteMemberUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.usecase.ListWorkspacesUseCase;
import com.collabspace.authworkspace.application.port.in.workspace.usecase.RemoveMemberUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/workspaces")
@Tag(name = "Workspaces", description = "Workspace management")
public class WorkspaceController {

	private final CreateWorkspaceUseCase createWorkspaceUseCase;

	private final InviteMemberUseCase inviteMemberUseCase;

	private final ChangeMemberRoleUseCase changeMemberRoleUseCase;

	private final RemoveMemberUseCase removeMemberUseCase;

	private final ListWorkspacesUseCase listWorkspacesUseCase;

	public WorkspaceController(ChangeMemberRoleUseCase changeMemberRoleUseCase,
			CreateWorkspaceUseCase createWorkspaceUseCase, InviteMemberUseCase inviteMemberUseCase,
			RemoveMemberUseCase removeMemberUseCase, ListWorkspacesUseCase listWorkspacesUseCase) {
		this.changeMemberRoleUseCase = changeMemberRoleUseCase;
		this.createWorkspaceUseCase = createWorkspaceUseCase;
		this.inviteMemberUseCase = inviteMemberUseCase;
		this.removeMemberUseCase = removeMemberUseCase;
		this.listWorkspacesUseCase = listWorkspacesUseCase;
	}

	@Operation(summary = "List workspaces",
			description = "Returns a cursor-paginated list of every workspace in the system")
	@ApiResponse(responseCode = "200", description = "List workspaces",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = PagedResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping()
	public ResponseEntity<PagedResponse<WorkspaceListItem>> getWorkspaces(
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@RequestParam(required = false) @ValidAfter String after) {
		UUID userId = currentUserId();
		String correlationId = MDC.get("correlationId");

		Optional<Instant> afterCreatedAt = Optional.empty();
		Optional<UUID> afterWorkspaceId = Optional.empty();
		if (after != null) {
			WorkspaceCursor cursor = WorkspaceCursor.decode(after);
			afterCreatedAt = Optional.of(cursor.createdAt());
			afterWorkspaceId = Optional.of(cursor.workspaceId());
		}

		ListWorkspacesCommand command = new ListWorkspacesCommand(userId, limit, afterCreatedAt, afterWorkspaceId,
				Optional.ofNullable(correlationId));

		ListWorkspacesResult result = listWorkspacesUseCase.list(command);

		List<WorkspaceListItem> items = result.workspaces()
			.stream()
			.map(entry -> new WorkspaceListItem(entry.id(), entry.name(), entry.memberCount()))
			.toList();

		String nextCursor = null;
		if (result.hasNextPage()) {
			WorkspaceCursor next = new WorkspaceCursor(result.nextAfterCreatedAt().orElseThrow(),
					result.nextAfterWorkspaceId().orElseThrow());
			nextCursor = next.encode();
		}

		PaginationMetadata pagination = new PaginationMetadata(result.hasNextPage(), nextCursor, limit, items.size());
		PagedResponse<WorkspaceListItem> response = new PagedResponse<>(items, pagination);

		return ResponseEntity.ok(response);
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

	@Operation(summary = "Change member role in workspace",
			description = "Admin changes members role in workspace to 'member' or 'admin'. Returns a new record of workspace information and users role information")
	@ApiResponse(responseCode = "200", description = "Change role successful",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ChangeMemberRoleResponse.class)))
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "403",
			description = "Caller is not a member of the workspace, or is a member without the admin role",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "404", description = "The user has no membership in this workspace",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "422", description = "Cannot leave workspace without admins",
			content = @Content(mediaType = "application/problem+json"))
	@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")
	@PatchMapping("/{workspaceId}/members/{memberId}")
	public ResponseEntity<ChangeMemberRoleResponse> changeMemberRole(@PathVariable UUID workspaceId,
			@PathVariable UUID memberId, @RequestBody @Valid ChangeMemberRoleRequest request,
			HttpServletRequest httpRequest) {
		UUID adminId = currentUserId();
		String ipAddress = ClientIpResolver.resolve(httpRequest);
		String correlationId = MDC.get("correlationId");

		ChangeMemberRoleCommand command = new ChangeMemberRoleCommand(adminId, workspaceId, memberId, request.role(),
				Optional.ofNullable(correlationId), Optional.of(ipAddress));

		ChangeMemberRoleResult result = changeMemberRoleUseCase.changeMemberRole(command);

		return ResponseEntity.ok(ChangeMemberRoleResponse.from(result));
	}

	@Operation(summary = "Remove a member from workspace",
			description = "Admin removes member from workspace. Returns a no content response code")
	@ApiResponse(responseCode = "204", description = "Member removed successful")
	@ApiResponse(responseCode = "400", description = "Validation failed",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "403",
			description = "Caller is not a member of the workspace, or is a member without the admin role",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "404", description = "The user has no membership in this workspace",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "422", description = "Cannot leave workspace without admins",
			content = @Content(mediaType = "application/problem+json"))
	@PreAuthorize("hasWorkspaceRole(#workspaceId, 'admin')")
	@DeleteMapping("/{workspaceId}/members/{memberId}")
	public ResponseEntity<Void> removeMember(@PathVariable UUID workspaceId, @PathVariable UUID memberId,
			HttpServletRequest httpRequest) {
		UUID adminId = currentUserId();
		String ipAddress = ClientIpResolver.resolve(httpRequest);
		String correlationId = MDC.get("correlationId");

		RemoveMemberCommand command = new RemoveMemberCommand(adminId, workspaceId, memberId,
				Optional.ofNullable(correlationId), Optional.of(ipAddress));

		removeMemberUseCase.removeMember(command);

		return ResponseEntity.noContent().build();
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
