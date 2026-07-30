package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DatabaseConnectivityTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void talksToRealPostgres() {
		Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
		assertThat(result).isEqualTo(1);
	}

}
