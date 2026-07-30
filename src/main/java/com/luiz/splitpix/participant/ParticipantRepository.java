package com.luiz.splitpix.participant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ParticipantRepository {

	private static final RowMapper<Participant> MAPPER = (rs, rowNum) -> new Participant(
			rs.getObject("id", UUID.class),
			rs.getObject("group_id", UUID.class),
			rs.getString("display_name"),
			rs.getString("pix_key_type") == null ? null : PixKeyType.valueOf(rs.getString("pix_key_type")),
			rs.getString("pix_key_value"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbcTemplate;

	public ParticipantRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(UUID id, UUID groupId, String displayName, PixKeyType pixKeyType, String pixKeyValue) {
		jdbcTemplate.update("""
				INSERT INTO participants (id, group_id, display_name, pix_key_type, pix_key_value)
				VALUES (?, ?, ?, ?, ?)
				""",
				id, groupId, displayName, pixKeyType == null ? null : pixKeyType.name(), pixKeyValue);
	}

	public List<Participant> findByGroupId(UUID groupId) {
		return jdbcTemplate.query("""
				SELECT id, group_id, display_name, pix_key_type, pix_key_value, created_at
				FROM participants
				WHERE group_id = ?
				ORDER BY created_at, id
				""",
				MAPPER, groupId);
	}

	public boolean pixKeyExistsInGroup(UUID groupId, String pixKeyValue) {
		Boolean exists = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM participants WHERE group_id = ? AND pix_key_value = ?
				)
				""",
				Boolean.class, groupId, pixKeyValue);
		return Boolean.TRUE.equals(exists);
	}

}
