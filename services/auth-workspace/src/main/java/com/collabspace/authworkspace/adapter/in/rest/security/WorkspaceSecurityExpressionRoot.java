package com.collabspace.authworkspace.adapter.in.rest.security;

import com.collabspace.authworkspace.adapter.in.rest.security.exception.InsufficientRoleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.NotAMemberException;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * spring-security-core's own MethodSecurityExpressionRoot (the class
 * DefaultMethodSecurityExpressionHandler normally creates) is package-private, so it
 * can't be extended -- this class re-implements the same
 * {@link MethodSecurityExpressionOperations} boilerplate on top of the public
 * {@link SecurityExpressionRoot}, purely to get a place to add
 * {@link #hasWorkspaceRole(UUID, String)}.
 */
public class WorkspaceSecurityExpressionRoot extends SecurityExpressionRoot<MethodInvocation>
		implements MethodSecurityExpressionOperations {

	private @Nullable Object filterObject;

	private @Nullable Object returnObject;

	private @Nullable Object target;

	public WorkspaceSecurityExpressionRoot(Supplier<? extends @Nullable Authentication> authentication,
			MethodInvocation invocation) {
		super(authentication, invocation);
	}

	/**
	 * No DB call -- membership data is already in the JWT's memberships claim, parsed
	 * into {@link WorkspaceAuthority} grants by HeaderAuthenticationFilter (PR 7) before
	 * this expression ever evaluates. See authorization.md "How authorization works at
	 * runtime".
	 */
	public boolean hasWorkspaceRole(UUID workspaceId, String role) {
		String targetWorkspaceId = workspaceId.toString();

		for (GrantedAuthority authority : getAuthentication().getAuthorities()) {
			if (authority instanceof WorkspaceAuthority workspaceAuthority
					&& workspaceAuthority.workspaceId().equals(targetWorkspaceId)) {
				if (!workspaceAuthority.role().equals(role)) {
					throw new InsufficientRoleException("Requires role " + role + " in workspace " + targetWorkspaceId);
				}
				return true;
			}
		}

		throw new NotAMemberException("Not a member of workspace " + targetWorkspaceId);
	}

	@Override
	public void setFilterObject(Object filterObject) {
		this.filterObject = filterObject;
	}

	@Override
	public @Nullable Object getFilterObject() {
		return this.filterObject;
	}

	@Override
	public void setReturnObject(@Nullable Object returnObject) {
		this.returnObject = returnObject;
	}

	@Override
	public @Nullable Object getReturnObject() {
		return this.returnObject;
	}

	void setThis(@Nullable Object target) {
		this.target = target;
	}

	@Override
	public @Nullable Object getThis() {
		return this.target;
	}

}
