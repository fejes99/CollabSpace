package com.collabspace.authworkspace.adapter.in.rest.security;

import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public class WorkspaceMethodSecurityExpressionHandler extends DefaultMethodSecurityExpressionHandler {

	/**
	 * PreAuthorizeAuthorizationManager calls this overload (Supplier-based) directly --
	 * DefaultMethodSecurityExpressionHandler's own implementation builds its root object
	 * via a *private* helper, so overriding the Authentication-based
	 * createSecurityExpressionRoot below is never reached on this path. This is the
	 * override that actually takes effect for @PreAuthorize.
	 */
	@Override
	public EvaluationContext createEvaluationContext(Supplier<? extends @Nullable Authentication> authentication,
			MethodInvocation mi) {
		WorkspaceSecurityExpressionRoot root = createWorkspaceExpressionRoot(authentication, mi);
		Class<?> targetClass = (mi.getThis() != null) ? AopProxyUtils.ultimateTargetClass(mi.getThis()) : null;
		Method specificMethod = AopUtils.getMostSpecificMethod(mi.getMethod(), targetClass);
		MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(root, specificMethod, mi.getArguments(),
				getParameterNameDiscoverer());
		if (getBeanResolver() != null) {
			context.setBeanResolver(getBeanResolver());
		}
		return context;
	}

	/**
	 * Kept for the Authentication-based (non-lazy) callers still present elsewhere in
	 * MethodSecurityExpressionHandler's contract -- not exercised by @PreAuthorize itself
	 * in this version, but a legitimate extension point regardless.
	 */
	@Override
	protected MethodSecurityExpressionOperations createSecurityExpressionRoot(@Nullable Authentication authentication,
			MethodInvocation invocation) {
		return createWorkspaceExpressionRoot(() -> authentication, invocation);
	}

	private WorkspaceSecurityExpressionRoot createWorkspaceExpressionRoot(
			Supplier<? extends @Nullable Authentication> authentication, MethodInvocation invocation) {
		WorkspaceSecurityExpressionRoot root = new WorkspaceSecurityExpressionRoot(authentication, invocation);
		root.setThis(invocation.getThis());
		root.setAuthorizationManagerFactory(getAuthorizationManagerFactory());
		root.setPermissionEvaluator(getPermissionEvaluator());
		return root;
	}

}
