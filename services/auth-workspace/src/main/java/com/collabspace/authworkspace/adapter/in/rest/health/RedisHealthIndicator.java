package com.collabspace.authworkspace.adapter.in.rest.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

@Component
class RedisHealthIndicator implements HealthIndicator {

	private static final Logger log = LoggerFactory.getLogger(RedisHealthIndicator.class);

	private static final String REDIS_URL_PROPERTY = "spring.data.redis.url";

	private final StringRedisTemplate redisTemplate;

	private final String host;

	private final AtomicReference<Boolean> lastStatus = new AtomicReference<>(null);

	RedisHealthIndicator(StringRedisTemplate redisTemplate, Environment environment) {
		this.redisTemplate = redisTemplate;
		String url = environment.getProperty(REDIS_URL_PROPERTY);
		this.host = extractHost(url);
		if (url == null || url.isBlank()) {
			log.warn("event=redis_url_not_configured msg=\"falling back to default host, verify SSM/env wiring\"");
		}
		else {
			log.info("event=redis_client_initialized host={}", host);
		}
	}

	@Override
	public Health health() {
		try {
			String pong = redisTemplate.execute(RedisConnection::ping);
			if ("PONG".equalsIgnoreCase(pong)) {
				logTransition(true);
				return Health.up().build();
			}
			logTransition(false);
			return Health.down().build();
		}
		catch (Exception _) {
			logTransition(false);
			return Health.down().build();
		}
	}

	private void logTransition(boolean up) {
		Boolean previous = lastStatus.getAndSet(up);
		if (up) {
			if (Boolean.FALSE.equals(previous)) {
				log.info("event=redis.health.recovered previousStatus=DOWN host={}", host);
			}
		}
		else {
			if (previous == null) {
				log.warn("event=redis.health.down previousStatus=UNKNOWN host={}", host);
			}
			else if (previous) {
				log.warn("event=redis.health.down previousStatus=UP host={}", host);
			}
		}
	}

	private static String extractHost(String url) {
		if (url == null || url.isBlank()) {
			return "unknown";
		}
		try {
			URI uri = URI.create(url);
			return uri.getHost() != null ? uri.getHost() : "unknown";
		}
		catch (Exception _) {
			return "unknown";
		}
	}

}
