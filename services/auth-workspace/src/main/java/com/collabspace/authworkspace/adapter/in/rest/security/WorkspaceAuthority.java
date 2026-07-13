package com.collabspace.authworkspace.adapter.in.rest.security;

import org.springframework.security.core.GrantedAuthority;

public record WorkspaceAuthority(String workspaceId, String role) implements GrantedAuthority {

	// Satisfies GrantedAuthority's contract only -- never parsed back. Real access goes
	// through workspaceId()/role() directly.
	@Override
	public String getAuthority() {
		return workspaceId + ":" + role;
	}

}
