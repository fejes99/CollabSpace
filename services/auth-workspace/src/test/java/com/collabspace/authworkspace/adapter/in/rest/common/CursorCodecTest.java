package com.collabspace.authworkspace.adapter.in.rest.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CursorCodec")
class CursorCodecTest {

	@Test
	@DisplayName("decode(encode()) round-trips arbitrary fields")
	void encodeThenDecodeRoundTrips() {
		Map<String, String> fields = Map.of("createdAt", "2026-04-15T10:32:00Z", "workspaceId",
				"7c9e6fe0-c305-400c-983f-9a0f4e9d8f4d");

		Map<String, String> decoded = CursorCodec.decode(CursorCodec.encode(fields));

		assertThat(decoded).isEqualTo(fields);
	}

	@Test
	@DisplayName("encode() produces an opaque Base64 string, not raw JSON")
	void encodeProducesBase64NotRawJson() {
		String cursor = CursorCodec.encode(Map.of("foo", "bar"));

		assertThat(cursor).doesNotContain("foo", "bar", "{", "}");
		assertThat(Base64.getDecoder().decode(cursor)).asString().contains("foo", "bar");
	}

	@Test
	@DisplayName("rejects a value that isn't valid Base64")
	void decodeRejectsInvalidBase64() {
		assertThatThrownBy(() -> CursorCodec.decode("not-valid-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects Base64 that decodes to something other than JSON")
	void decodeRejectsBase64ThatIsNotJson() {
		String notJson = Base64.getEncoder().encodeToString("just plain text".getBytes());

		assertThatThrownBy(() -> CursorCodec.decode(notJson)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects Base64 that decodes to a JSON object with no fields")
	void decodeRejectsEmptyObject() {
		String emptyObject = Base64.getEncoder().encodeToString("{}".getBytes());

		assertThatThrownBy(() -> CursorCodec.decode(emptyObject)).isInstanceOf(IllegalArgumentException.class);
	}

}
