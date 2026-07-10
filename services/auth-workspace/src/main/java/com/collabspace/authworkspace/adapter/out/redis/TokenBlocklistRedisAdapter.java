package com.collabspace.authworkspace.adapter.out.redis;

import com.collabspace.authworkspace.application.port.out.auth.TokenBlocklistRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenBlocklistRedisAdapter implements TokenBlocklistRepository {

	private final StringRedisTemplate redisTemplate;

	public TokenBlocklistRedisAdapter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public boolean isBlocklisted(String jti) {
		// TODO: GET blocklist:<jti>; fail open (return false) on Redis connection error,
		// per plan §5.
		return false;
	}

}
