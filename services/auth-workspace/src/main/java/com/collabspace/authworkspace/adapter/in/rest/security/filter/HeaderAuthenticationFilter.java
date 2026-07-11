package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.WorkspaceAuthority;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.SecurityAuthenticationException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.UnexpectedIdentityException;
import com.collabspace.authworkspace.domain.model.auth.WorkspaceMembership;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String WORKSPACES_HEADER = "X-User-Workspaces";

	// Routes that never carry identity headers, by design -- either they don't go
	// through the JWT authorizer at all (.well-known, actuator/health/**) or they're
	// the pre-authentication auth endpoints themselves (register, login).
	private static final Set<String> ANONYMOUS_PATHS = Set.of("/v1/auth/register", "/v1/auth/login", "/actuator/health",
			"/actuator/health/readiness", "/actuator/health/liveness");

	private static final String WELL_KNOWN_PATH_PREFIX = "/.well-known/";

	private static final int MAX_WORKSPACES_HEADER_BYTES = 4096;

	// Reduced from the plan's original 200: at ~38 bytes for a realistic minimal entry,
	// 200 entries (~7.6KB) would always exceed MAX_WORKSPACES_HEADER_BYTES first, making
	// this check unreachable dead code. 100 entries (~3.9KB at minimum size) stays under
	// the byte limit, so this check is the one that actually fires for a too-long list of
	// small memberships -- still generous for how many workspaces one user plausibly
	// belongs to in a v1 product.
	private static final int MAX_WORKSPACES_ENTRIES = 100;

	private final ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public HeaderAuthenticationFilter(ProblemDetailsSecurityHandler problemDetailsSecurityHandler) {
		this.problemDetailsSecurityHandler = problemDetailsSecurityHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String userId = request.getHeader(USER_ID_HEADER);
		String workspacesHeader = request.getHeader(WORKSPACES_HEADER);

		try {
			if (!isAnonymousRequest(request, userId, workspacesHeader)) {
				List<WorkspaceMembership> memberships = parseWorkspaces(workspacesHeader);
				SecurityContextHolder.getContext()
					.setAuthentication(
							new PreAuthenticatedAuthenticationToken(userId, null, toAuthorities(memberships)));
			}
		}
		catch (SecurityAuthenticationException ex) {
			reject(request, response, ex);
			return;
		}

		filterChain.doFilter(request, response);
	}

	// Returns true when this request should proceed with no identity -- an anonymous
	// route with neither header present. Throws on every invalid combination described
	// in plan security-filter.md §4's validation table; a normal return means "userId
	// and workspacesHeader are both present and this is a route that expects them."
	private boolean isAnonymousRequest(HttpServletRequest request, String userId, String workspacesHeader) {
		boolean userIdPresent = userId != null;
		boolean workspacesPresent = workspacesHeader != null;
		boolean anonymousRoute = isAnonymousRoute(request);

		if (userIdPresent != workspacesPresent) {
			throw new MalformedIdentityHeadersException(
					"X-User-Id and X-User-Workspaces must be present or absent together");
		}
		if (!userIdPresent && anonymousRoute) {
			return true;
		}
		if (!userIdPresent) {
			throw new MalformedIdentityHeadersException("X-User-Id is required on this route");
		}
		if (anonymousRoute) {
			throw new UnexpectedIdentityException("Identity headers must not be present on " + request.getRequestURI());
		}
		if (!StringUtils.hasText(userId)) {
			throw new MalformedIdentityHeadersException("X-User-Id must not be blank");
		}
		return false;
	}

	private boolean isAnonymousRoute(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return ANONYMOUS_PATHS.contains(uri) || uri.startsWith(WELL_KNOWN_PATH_PREFIX);
	}

	// Size limits enforced before parsing (byte length) and after (entry count, which
	// can only be known once parsed) -- see plan §4.
	private List<WorkspaceMembership> parseWorkspaces(String workspacesHeader) {
		if (!StringUtils.hasText(workspacesHeader)) {
			throw new MalformedIdentityHeadersException("X-User-Workspaces must not be blank");
		}
		if (workspacesHeader.getBytes(StandardCharsets.UTF_8).length > MAX_WORKSPACES_HEADER_BYTES) {
			throw new MalformedIdentityHeadersException("X-User-Workspaces exceeds the 4KB size limit");
		}

		List<WorkspaceMembership> memberships;
		try {
			memberships = objectMapper.readValue(workspacesHeader, new TypeReference<>() {
			});
		}
		catch (JsonProcessingException ex) {
			throw new MalformedIdentityHeadersException("X-User-Workspaces is not valid JSON");
		}

		if (memberships.size() > MAX_WORKSPACES_ENTRIES) {
			throw new MalformedIdentityHeadersException("X-User-Workspaces exceeds the 100-entry limit");
		}
		return memberships;
	}

	private List<GrantedAuthority> toAuthorities(List<WorkspaceMembership> memberships) {
		return memberships.stream()
			.map(membership -> (GrantedAuthority) new WorkspaceAuthority(membership.workspaceId(), membership.role()))
			.toList();
	}

	private void reject(HttpServletRequest request, HttpServletResponse response, SecurityAuthenticationException ex)
			throws IOException, ServletException {
		problemDetailsSecurityHandler.commence(request, response, ex);
	}

}
