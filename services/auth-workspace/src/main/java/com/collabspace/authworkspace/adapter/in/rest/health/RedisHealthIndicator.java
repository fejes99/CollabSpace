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

	// Checked separately from REDIS_URL_PROPERTY: application.properties binds
	// spring.data.redis.url to a valid fallback (redis://localhost:6379) when
	// SPRING_DATA_REDIS_URL is absent, so REDIS_URL_PROPERTY is never blank -
	// the raw env var name is the only reliable signal for "was this actually
	// configured, or silently defaulted."
	private static final String REDIS_URL_ENV_VAR = "SPRING_DATA_REDIS_URL";

	private final StringRedisTemplate redisTemplate;

	private final String host;

	private final AtomicReference<Boolean> lastStatus = new AtomicReference<>(null);

	RedisHealthIndicator(StringRedisTemplate redisTemplate, Environment environment) {
		this.redisTemplate = redisTemplate;
		this.host = extractHost(environment.getProperty(REDIS_URL_PROPERTY));
		if (environment.containsProperty(REDIS_URL_ENV_VAR)) {
			log.info("event=redis_client_initialized host={}", host);
		}
		else {
			log.warn("event=redis_url_not_configured msg=\"falling back to default host, verify SSM/env wiring\"");
		}
	}

	@Override
	public Health health() {
		try {
			String pong = redisTemplate.execute(RedisConnection::ping);
			if ("PONG".equalsIgnoreCase(pong)) {
				logTransition(true, null);
				return Health.up().build();
			}
			logTransition(false, null);
			return Health.down().build();
		}
		catch (Exception e) {
			logTransition(false, e);
			return Health.down().build();
		}
	}

	private void logTransition(boolean up, Exception exception) {
		Boolean previous = lastStatus.getAndSet(up);
		if (up) {
			if (Boolean.FALSE.equals(previous)) {
				log.info("event=redis.health.recovered previousStatus=DOWN host={}", host);
			}
		}
		else {
			String cause = exception != null ? exception.getClass().getSimpleName() + ": " + exception.getMessage()
					: "non-PONG response";
			if (previous == null) {
				log.warn("event=redis.health.down previousStatus=UNKNOWN host={} cause=\"{}\"", host, cause);
			}
			else if (previous) {
				log.warn("event=redis.health.down previousStatus=UP host={} cause=\"{}\"", host, cause);
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
