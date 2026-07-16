package com.collabspace.authworkspace.adapter.out.redis;

import com.collabspace.authworkspace.application.port.out.workspace.MembershipStalenessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class MembershipStalenessRedisAdapter implements MembershipStalenessRepository {

	private static final Logger log = LoggerFactory.getLogger(MembershipStalenessRedisAdapter.class);

	private static final String MARKER_KEY_PREFIX = "membership-changed-at:";

	// See ADR-032 for why 17 minutes (access token lifetime + buffer).
	private static final Duration MARKER_TTL = Duration.ofMinutes(17);

	private final StringRedisTemplate redisTemplate;

	public MembershipStalenessRedisAdapter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	// No internal fail-open, unlike TokenBlocklistRedisAdapter -- this has exactly
	// one caller (WorkspaceApplicationService's afterCommit), which already owns
	// the fail-open try/catch and its own event=membership_marker_write_failed
	// logging per the invite-member plan. Swallowing the exception here would
	// silently defeat that logging -- the caller would never know it happened.
	@Override
	public void markMembershipChanged(UUID userId, Instant changedAt) {
		redisTemplate.opsForValue()
			.set(MARKER_KEY_PREFIX + userId, String.valueOf(changedAt.getEpochSecond()), MARKER_TTL);
	}

	// Fails open like TokenBlocklistRepository.isBlocklisted -- this has many callers
	// (every authenticated request via MembershipStalenessFilter), unlike the write
	// side above, so a uniform fail-open policy belongs in the adapter itself rather
	// than in each caller.
	@Override
	public Optional<Instant> findMembershipChangedAt(UUID userId) {
		try {
			String value = redisTemplate.opsForValue().get(MARKER_KEY_PREFIX + userId);
			return Optional.ofNullable(value).map(v -> Instant.ofEpochSecond(Long.parseLong(v)));
		}
		catch (DataAccessException ex) {
			log.warn("event=membership_staleness_check_unavailable userId={} reason={}", userId, ex.getMessage());
			return Optional.empty();
		}
	}

}
