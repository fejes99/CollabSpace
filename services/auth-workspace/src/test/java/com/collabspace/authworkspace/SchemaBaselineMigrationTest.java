package com.collabspace.authworkspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestContainersConfiguration.class)
class SchemaBaselineMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Test
	void usersTableHasCorrectSchema() {
		assertThat(tableExists("users")).isTrue();

		assertColumn("id", "uuid", "NO", null);
		assertColumn("name", "character varying", "NO", 255);
		assertColumn("email", "character varying", "NO", 320);
		assertColumn("password_hash", "text", "YES", null);
		assertColumn("created_at", "timestamp with time zone", "NO", null);
		assertColumn("updated_at", "timestamp with time zone", "NO", null);
	}

	@Test
	void emailColumnHasUniqueConstraint() {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_name = kcu.constraint_name
				 AND tc.table_name = kcu.table_name
				WHERE tc.constraint_type = 'UNIQUE'
				  AND tc.table_name = 'users'
				  AND kcu.column_name = 'email'
				""", Integer.class);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void migrationIsIdempotent() {
		var result = flyway.migrate();
		assertThat(result.migrationsExecuted).isZero();
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
				Integer.class, tableName);
		return count != null && count == 1;
	}

	private void assertColumn(String column, String expectedType, String expectedNullable, Integer expectedLength) {
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT data_type, is_nullable, character_maximum_length FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = ?",
				column);
		assertThat(row.get("data_type")).as("data_type for %s", column).hasToString(expectedType);
		assertThat(row.get("is_nullable")).as("is_nullable for %s", column).hasToString(expectedNullable);
		if (expectedLength != null) {
			assertThat(((Number) row.get("character_maximum_length")).intValue())
				.as("character_maximum_length for %s", column)
				.isEqualTo(expectedLength);
		}
	}

}
