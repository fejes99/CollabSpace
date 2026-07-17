package com.collabspace.authworkspace.adapter.in.rest.security.filter;

import com.collabspace.authworkspace.adapter.in.rest.security.ProblemDetailsSecurityHandler;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.ClaimsStaleException;
import com.collabspace.authworkspace.adapter.in.rest.security.exception.MalformedIdentityHeadersException;
import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Compares X-JWT-Iat against the membership-changed-at:<userId> Redis marker (ADR-032) --
// the read side of the other-directed membership-change mechanism PR 9 (invite-member)
// writes. Absent header, or no marker for this user (never had an other-directed change),
// both pass -- mirrors JwtBlocklistFilter's "absent jti passes" rule. Unlike that case,
// X-User-Id present without X-JWT-Iat is logged (not just silently passed): the two always
// travel together from the same JWT claims once API Gateway is wired correctly, so seeing
// one without the other is itself a signal of a claim-mapping regression -- exactly the
// class of bug ADR-036 found, undetected for two PRs, with zero log signal.
@Component
public class MembershipStalenessFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(MembershipStalenessFilter.class);

	private static final String USER_ID_HEADER = "X-User-Id";

	private static final String IAT_HEADER = "X-JWT-Iat";

	private final MembershipStalenessRepository membershipStalenessRepository;

	private final ProblemDetailsSecurityHandler problemDetailsSecurityHandler;

	public MembershipStalenessFilter(MembershipStalenessRepository membershipStalenessRepository,
			ProblemDetailsSecurityHandler problemDetailsSecurityHandler) {
		this.membershipStalenessRepository = membershipStalenessRepository;
		this.problemDetailsSecurityHandler = problemDetailsSecurityHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String userId = request.getHeader(USER_ID_HEADER);
		String iatHeader = request.getHeader(IAT_HEADER);

		if (userId != null && iatHeader == null) {
			log.warn("event=membership_staleness_iat_header_missing userId={} correlationId={}", userId,
					MDC.get("correlationId"));
		}

		if (userId != null && iatHeader != null) {
			boolean stale;
			try {
				stale = isStale(userId, iatHeader);
			}
			catch (IllegalArgumentException ex) {
				// Covers both UUID.fromString (X-User-Id) and Long.parseLong (X-JWT-Iat,
				// via its NumberFormatException subtype) -- mirrors
				// HeaderAuthenticationFilter's
				// own treatment of malformed identity headers earlier in this same chain,
				// rather than letting either throw raw past GlobalExceptionHandler's
				// reach.
				problemDetailsSecurityHandler.commence(request, response,
						new MalformedIdentityHeadersException("X-User-Id or X-JWT-Iat is malformed"));
				return;
			}
			if (stale) {
				log.warn("event=claims_stale_rejected userId={} jti={} correlationId={}", userId,
						request.getHeader("X-JWT-Jti"), MDC.get("correlationId"));
				problemDetailsSecurityHandler.commence(request, response,
						new ClaimsStaleException("Token issued before the last membership change"));
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private boolean isStale(String userId, String iatHeader) {
		Optional<Instant> changedAt = membershipStalenessRepository.findMembershipChangedAt(UUID.fromString(userId));
		if (changedAt.isEmpty()) {
			return false;
		}
		Instant iat = Instant.ofEpochSecond(Long.parseLong(iatHeader));
		return !changedAt.get().isBefore(iat);
	}

}
