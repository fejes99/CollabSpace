package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.adapter.in.rest.security.exception.InsufficientRoleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.NotAMemberException;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceSecurityExpressionRoot")
class WorkspaceSecurityExpressionRootTest {

	@Mock
	private Authentication authentication;

	@Mock
	private MethodInvocation methodInvocation;

	private WorkspaceSecurityExpressionRoot root;

	@BeforeEach
	void setUp() {
		root = new WorkspaceSecurityExpressionRoot(() -> authentication, methodInvocation);
	}

	@Test
	@DisplayName("returns true when the caller has the required role in the target workspace")
	void returnsTrueWhenCallerHasRequiredRoleInTargetWorkspace() {
		UUID workspaceId = UUID.randomUUID();
		mockAuthorities(new WorkspaceAuthority(workspaceId.toString(), "admin"));

		boolean result = root.hasWorkspaceRole(workspaceId, "admin");

		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("throws InsufficientRoleException when the caller has a different role in the target workspace")
	void throwsInsufficientRoleExceptionWhenCallerHasDifferentRoleInTargetWorkspace() {
		UUID workspaceId = UUID.randomUUID();
		mockAuthorities(new WorkspaceAuthority(workspaceId.toString(), "member"));

		assertThatThrownBy(() -> root.hasWorkspaceRole(workspaceId, "admin"))
			.isInstanceOf(InsufficientRoleException.class);
	}

	@Test
	@DisplayName("throws NotAMemberException when the caller has the required role in a different workspace only")
	void throwsNotAMemberExceptionWhenCallerHasRoleInDifferentWorkspaceOnly() {
		UUID targetWorkspaceId = UUID.randomUUID();
		UUID otherWorkspaceId = UUID.randomUUID();
		mockAuthorities(new WorkspaceAuthority(otherWorkspaceId.toString(), "admin"));

		assertThatThrownBy(() -> root.hasWorkspaceRole(targetWorkspaceId, "admin"))
			.isInstanceOf(NotAMemberException.class);
	}

	@Test
	@DisplayName("throws NotAMemberException when the caller has no memberships at all")
	void throwsNotAMemberExceptionWhenCallerHasNoMemberships() {
		UUID workspaceId = UUID.randomUUID();
		mockAuthorities();

		assertThatThrownBy(() -> root.hasWorkspaceRole(workspaceId, "admin")).isInstanceOf(NotAMemberException.class);
	}

	@Test
	@DisplayName("throws NotAMemberException, not ClassCastException, when authorities contain a non-WorkspaceAuthority")
	void throwsNotAMemberExceptionWhenAuthoritiesContainNonWorkspaceAuthority() {
		UUID workspaceId = UUID.randomUUID();
		GrantedAuthority unrelatedAuthority = () -> "ROLE_SOMETHING_ELSE";
		mockAuthorities(unrelatedAuthority);

		assertThatThrownBy(() -> root.hasWorkspaceRole(workspaceId, "admin")).isInstanceOf(NotAMemberException.class);
	}

	private void mockAuthorities(GrantedAuthority... authorities) {
		doReturn(List.of(authorities)).when(authentication).getAuthorities();
	}

}
