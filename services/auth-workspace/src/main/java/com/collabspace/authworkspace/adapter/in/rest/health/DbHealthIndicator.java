package com.collabspace.authworkspace.adapter.in.rest.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

@Component
class DbHealthIndicator implements HealthIndicator {

	private static final Logger log = LoggerFactory.getLogger(DbHealthIndicator.class);

	private final DataSource dataSource;

	private final String host;

	private final AtomicReference<Boolean> lastStatus = new AtomicReference<>(null);

	DbHealthIndicator(DataSource dataSource, DataSourceProperties properties) {
		this.dataSource = dataSource;
		this.host = extractHost(properties.getUrl());
	}

	@Override
	public Health health() {
		try (Connection connection = dataSource.getConnection()) {
			if (!connection.isValid(1)) {
				logTransition(false);
				return Health.down().build();
			}
			logTransition(true);
			return Health.up().build();
		}
		catch (SQLException _) {
			logTransition(false);
			return Health.down().build();
		}
	}

	private void logTransition(boolean up) {
		Boolean previous = lastStatus.getAndSet(up);
		if (up) {
			if (Boolean.FALSE.equals(previous)) {
				log.info("event=db.health.recovered previousStatus=DOWN host={}", host);
			}
		}
		else {
			if (previous == null) {
				log.warn("event=db.health.down previousStatus=UNKNOWN host={}", host);
			}
			else if (Boolean.TRUE.equals(previous)) {
				log.warn("event=db.health.down previousStatus=UP host={}", host);
			}
		}
	}

	private static String extractHost(String jdbcUrl) {
		if (jdbcUrl == null || jdbcUrl.isBlank()) {
			return "unknown";
		}
		try {
			String withoutScheme = jdbcUrl.substring("jdbc:postgresql://".length());
			String hostAndPort = withoutScheme.split("/")[0];
			return hostAndPort.split(":")[0];
		}
		catch (Exception _) {
			return "unknown";
		}
	}

}
