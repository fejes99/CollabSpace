package com.collabspace.authworkspace.adapter.in.rest.security;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceMethodSecurityExpressionHandler")
class WorkspaceMethodSecurityExpressionHandlerTest {

	@Mock
	private Authentication authentication;

	@Mock
	private MethodInvocation methodInvocation;

	private final WorkspaceMethodSecurityExpressionHandler handler = new WorkspaceMethodSecurityExpressionHandler();

	static class AnnotatedTarget {

		void inviteMember(UUID workspaceId) {
		}

	}

	// createEvaluationContext(Supplier, MethodInvocation) is the overload
	// PreAuthorizeAuthorizationManager actually calls -- see the class's own doc
	// comment. This test exists so a regression that silently stops overriding it
	// (falling back to spring-security-core's own, unaware of hasWorkspaceRole) fails
	// here directly, instead of only showing up as a coarser @PreAuthorize integration
	// failure.
	@Test
	@DisplayName("builds an evaluation context whose root is a WorkspaceSecurityExpressionRoot wired to the invocation")
	void createEvaluationContextBuildsWorkspaceExpressionRoot() throws NoSuchMethodException {
		Method method = AnnotatedTarget.class.getDeclaredMethod("inviteMember", UUID.class);
		AnnotatedTarget target = new AnnotatedTarget();
		when(methodInvocation.getMethod()).thenReturn(method);
		when(methodInvocation.getThis()).thenReturn(target);
		when(methodInvocation.getArguments()).thenReturn(new Object[] { UUID.randomUUID() });

		EvaluationContext context = handler.createEvaluationContext(() -> authentication, methodInvocation);

		Object root = context.getRootObject().getValue();
		assertThat(root).isInstanceOf(WorkspaceSecurityExpressionRoot.class);
		assertThat(((WorkspaceSecurityExpressionRoot) root).getThis()).isSameAs(target);
	}

}
