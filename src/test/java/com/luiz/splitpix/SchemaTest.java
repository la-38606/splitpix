package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void allTablesExist() {
		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class);
		assertThat(tables).contains("groups", "participants", "expenses", "expense_shares", "settlements");
	}

	@Test
	void schemaIsIdempotent() throws IOException {
		// Re-running the init script against an already-initialized database must be a no-op.
		String schema;
		try (var in = getClass().getResourceAsStream("/schema.sql")) {
			schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		jdbcTemplate.execute(schema);
	}

}
