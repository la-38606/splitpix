package com.luiz.splitpix.group;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRepository {

	private static final RowMapper<Group> MAPPER = (rs, rowNum) -> new Group(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("invite_token"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbcTemplate;

	public GroupRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(UUID id, String name, String inviteToken) {
		jdbcTemplate.update("INSERT INTO groups (id, name, invite_token) VALUES (?, ?, ?)",
				id, name, inviteToken);
	}

	public Optional<Group> findById(UUID id) {
		return jdbcTemplate.query(
				"SELECT id, name, invite_token, created_at FROM groups WHERE id = ?",
				MAPPER, id)
				.stream().findFirst();
	}

}
