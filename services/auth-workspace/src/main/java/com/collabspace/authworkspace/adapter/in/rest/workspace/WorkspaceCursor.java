package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.adapter.in.rest.common.CursorCodec;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WorkspaceCursor(Instant createdAt, UUID workspaceId) {

	public static WorkspaceCursor decode(String value) {
		try {
			Map<String, String> fields = CursorCodec.decode(value);
			return new WorkspaceCursor(Instant.parse(fields.get("createdAt")),
					UUID.fromString(fields.get("workspaceId")));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid cursor", ex);
		}
	}

	public String encode() {
		return CursorCodec.encode(Map.of("createdAt", createdAt.toString(), "workspaceId", workspaceId.toString()));
	}

}
