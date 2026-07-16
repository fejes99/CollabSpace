package com.collabspace.authworkspace.adapter.in.rest.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoleValidator")
class RoleValidatorTest {

	// isValid never touches its ConstraintValidatorContext parameter (delegates entirely
	// to WorkspaceRole.fromString), so passing null is simpler and just as valid as
	// mocking an object that's never actually used.
	private final RoleValidator validator = new RoleValidator();

	@Test
	@DisplayName("accepts null -- role is optional, absence is a defaulting concern, not a validity concern")
	void acceptsNull() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

	@Test
	@DisplayName("accepts a valid lowercase role")
	void acceptsValidLowercaseRole() {
		assertThat(validator.isValid("admin", null)).isTrue();
		assertThat(validator.isValid("member", null)).isTrue();
	}

	@Test
	@DisplayName("accepts a mixed-case role")
	void acceptsMixedCaseRole() {
		assertThat(validator.isValid("Admin", null)).isTrue();
		assertThat(validator.isValid("MEMBER", null)).isTrue();
	}

	@Test
	@DisplayName("accepts a role with surrounding whitespace")
	void acceptsRoleWithWhitespace() {
		assertThat(validator.isValid(" admin ", null)).isTrue();
	}

	@Test
	@DisplayName("rejects an unknown role")
	void rejectsUnknownRole() {
		assertThat(validator.isValid("owner", null)).isFalse();
	}

	@Test
	@DisplayName("rejects a blank string")
	void rejectsBlankString() {
		assertThat(validator.isValid("   ", null)).isFalse();
	}

}
