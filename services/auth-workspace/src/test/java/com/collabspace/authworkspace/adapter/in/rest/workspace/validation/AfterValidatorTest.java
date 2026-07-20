package com.collabspace.authworkspace.adapter.in.rest.workspace.validation;

import com.collabspace.authworkspace.adapter.in.rest.workspace.WorkspaceCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AfterValidator")
class AfterValidatorTest {

	private final AfterValidator validator = new AfterValidator();

	@Test
	@DisplayName("accepts null -- after is optional, absence is a first-page concern, not a validity concern")
	void acceptsNull() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

	@Test
	@DisplayName("accepts a well-formed cursor")
	void acceptsValidCursor() {
		String cursor = new WorkspaceCursor(Instant.parse("2026-04-15T10:32:00Z"), UUID.randomUUID()).encode();

		assertThat(validator.isValid(cursor, null)).isTrue();
	}

	@Test
	@DisplayName("rejects a value that isn't valid Base64")
	void rejectsInvalidBase64() {
		assertThat(validator.isValid("not-valid-base64!!!", null)).isFalse();
	}

	@Test
	@DisplayName("rejects Base64 that decodes to something other than JSON")
	void rejectsBase64ThatIsNotJson() {
		String notJson = Base64.getEncoder().encodeToString("just plain text".getBytes());

		assertThat(validator.isValid(notJson, null)).isFalse();
	}

	@Test
	@DisplayName("rejects JSON missing the workspaceId field")
	void rejectsMissingWorkspaceId() {
		String cursor = Base64.getEncoder().encodeToString("""
				{ "createdAt": "2026-04-15T10:32:00Z" }
				""".getBytes());

		assertThat(validator.isValid(cursor, null)).isFalse();
	}

	@Test
	@DisplayName("rejects an empty string")
	void rejectsEmptyString() {
		assertThat(validator.isValid("", null)).isFalse();
	}

}
