package com.luiz.splitpix.group;

import com.luiz.splitpix.common.NotFoundException;
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

	/**
	 * Serializes all writes within a group (design doc 13.3). Every write path
	 * that changes accounting state must call this inside its transaction.
	 * Throws instead of silently degrading to a no-op lock if the row is gone.
	 */
	public void lockById(UUID id) {
		if (jdbcTemplate.queryForList("SELECT id FROM groups WHERE id = ? FOR UPDATE", id).isEmpty()) {
			throw new NotFoundException("GROUP_NOT_FOUND");
		}
	}

}
