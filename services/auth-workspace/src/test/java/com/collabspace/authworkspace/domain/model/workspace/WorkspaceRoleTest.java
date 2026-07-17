package com.collabspace.authworkspace.domain.model.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkspaceRole")
class WorkspaceRoleTest {

	@Test
	@DisplayName("fromString resolves an exact lowercase match")
	void fromStringResolvesExactMatch() {
		assertThat(WorkspaceRole.fromString("admin")).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(WorkspaceRole.fromString("member")).isEqualTo(WorkspaceRole.MEMBER);
	}

	@Test
	@DisplayName("fromString is case-insensitive")
	void fromStringIsCaseInsensitive() {
		assertThat(WorkspaceRole.fromString("Admin")).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(WorkspaceRole.fromString("ADMIN")).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(WorkspaceRole.fromString("MEMBER")).isEqualTo(WorkspaceRole.MEMBER);
	}

	@Test
	@DisplayName("fromString trims surrounding whitespace")
	void fromStringTrimsWhitespace() {
		assertThat(WorkspaceRole.fromString(" admin ")).isEqualTo(WorkspaceRole.ADMIN);
		assertThat(WorkspaceRole.fromString("\tmember\n")).isEqualTo(WorkspaceRole.MEMBER);
	}

	@Test
	@DisplayName("fromString throws IllegalArgumentException for an unknown value")
	void fromStringThrowsForUnknownValue() {
		assertThatThrownBy(() -> WorkspaceRole.fromString("owner")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("fromString throws IllegalArgumentException for null")
	void fromStringThrowsForNull() {
		assertThatThrownBy(() -> WorkspaceRole.fromString(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("fromString throws IllegalArgumentException for blank input")
	void fromStringThrowsForBlank() {
		assertThatThrownBy(() -> WorkspaceRole.fromString("   ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("getValue returns the lowercase canonical string")
	void getValueReturnsLowercaseCanonicalString() {
		assertThat(WorkspaceRole.ADMIN.getValue()).isEqualTo("admin");
		assertThat(WorkspaceRole.MEMBER.getValue()).isEqualTo("member");
	}

}
