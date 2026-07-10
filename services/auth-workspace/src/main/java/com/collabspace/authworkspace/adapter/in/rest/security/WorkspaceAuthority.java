package com.collabspace.authworkspace.adapter.in.rest.security;

import org.springframework.security.core.GrantedAuthority;

public record WorkspaceAuthority(String workspaceId, String role) implements GrantedAuthority {

	@Override
	public String getAuthority() {
		// Structured fields (workspaceId(), role()) are what PR 8's hasWorkspaceRole
		// expression reads directly. This string form only exists to satisfy
		// GrantedAuthority's contract (logging/toString) and is not parsed back.
		return workspaceId + ":" + role;
	}

}
