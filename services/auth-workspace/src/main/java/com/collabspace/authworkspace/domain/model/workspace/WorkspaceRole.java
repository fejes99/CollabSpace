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
		if (value == null) {
			throw new IllegalArgumentException("Workspace role must not be null");
		}

		String normalized = value.trim();
		for (WorkspaceRole workspaceRole : WorkspaceRole.values()) {
			if (workspaceRole.getValue().equalsIgnoreCase(normalized)) {
				return workspaceRole;
			}
		}

		throw new IllegalArgumentException("Workspace role " + value + " doesn't exist");
	}

}
