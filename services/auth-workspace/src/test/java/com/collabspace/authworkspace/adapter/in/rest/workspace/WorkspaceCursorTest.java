package com.collabspace.authworkspace.adapter.in.rest.workspace;

import com.collabspace.authworkspace.adapter.in.rest.common.CursorCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkspaceCursor")
class WorkspaceCursorTest {

	private static final Instant CREATED_AT = Instant.parse("2026-04-15T10:32:00Z");

	private static final UUID WORKSPACE_ID = UUID.fromString("7c9e6fe0-c305-400c-983f-9a0f4e9d8f4d");

	@Test
	@DisplayName("decode(encode()) round-trips to the original createdAt and workspaceId")
	void encodeThenDecodeRoundTrips() {
		WorkspaceCursor original = new WorkspaceCursor(CREATED_AT, WORKSPACE_ID);

		WorkspaceCursor decoded = WorkspaceCursor.decode(original.encode());

		assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
		assertThat(decoded.workspaceId()).isEqualTo(WORKSPACE_ID);
	}

	@Test
	@DisplayName("rejects JSON missing the createdAt field")
	void decodeRejectsMissingCreatedAt() {
		String cursor = CursorCodec.encode(Map.of("workspaceId", WORKSPACE_ID.toString()));

		assertThatThrownBy(() -> WorkspaceCursor.decode(cursor)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects JSON missing the workspaceId field")
	void decodeRejectsMissingWorkspaceId() {
		String cursor = CursorCodec.encode(Map.of("createdAt", CREATED_AT.toString()));

		assertThatThrownBy(() -> WorkspaceCursor.decode(cursor)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects a createdAt that isn't a valid ISO-8601 instant")
	void decodeRejectsMalformedCreatedAt() {
		String cursor = CursorCodec
			.encode(Map.of("createdAt", "not-a-timestamp", "workspaceId", WORKSPACE_ID.toString()));

		assertThatThrownBy(() -> WorkspaceCursor.decode(cursor)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects a workspaceId that isn't a valid UUID")
	void decodeRejectsMalformedWorkspaceId() {
		String cursor = CursorCodec.encode(Map.of("createdAt", CREATED_AT.toString(), "workspaceId", "not-a-uuid"));

		assertThatThrownBy(() -> WorkspaceCursor.decode(cursor)).isInstanceOf(IllegalArgumentException.class);
	}

}
