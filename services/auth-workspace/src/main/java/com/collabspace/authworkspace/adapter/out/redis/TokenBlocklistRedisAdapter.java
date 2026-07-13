package com.collabspace.authworkspace.adapter.out.redis;

import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenBlocklistRedisAdapter implements TokenBlocklistRepository {

	private static final Logger log = LoggerFactory.getLogger(TokenBlocklistRedisAdapter.class);

	private static final String BLOCKLIST_KEY_PREFIX = "blocklist:";

	private final StringRedisTemplate redisTemplate;

	public TokenBlocklistRedisAdapter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public boolean isBlocklisted(String jti) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(BLOCKLIST_KEY_PREFIX + jti));
		}
		catch (DataAccessException ex) {
			// Fail open, per plan §5: a Redis outage must not reject all authenticated
			// traffic. Worse than the accepted 15-minute post-logout exposure window.
			log.warn("event=blocklist_check_unavailable jti={} reason={}", jti, ex.getMessage());
			return false;
		}
	}

}
