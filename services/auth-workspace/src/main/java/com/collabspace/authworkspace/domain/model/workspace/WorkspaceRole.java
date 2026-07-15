package com.collabspace.authworkspace.domain.model.workspace;

public enum WorkspaceRole {

	ADMIN("admin"), MEMBER("member");

	private final String value;

	WorkspaceRole(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public static WorkspaceRole fromString(String value) {
		for (WorkspaceRole workspaceRole : WorkspaceRole.values()) {
			if (workspaceRole.getValue().equals(value)) {
				return workspaceRole;
			}
		}

		throw new IllegalArgumentException("Workspace role " + value + " doesn't exist");
	}

}
