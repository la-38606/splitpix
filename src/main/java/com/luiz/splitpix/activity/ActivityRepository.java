package com.luiz.splitpix.activity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityRepository {

	private static final RowMapper<ActivityItem> MAPPER = (rs, rowNum) -> new ActivityItem(
			rs.getString("type"),
			rs.getObject("id", UUID.class),
			rs.getString("description"),
			rs.getObject("payer_participant_id", UUID.class),
			rs.getObject("recipient_participant_id", UUID.class),
			rs.getLong("amount_cents"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbcTemplate;

	public ActivityRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<ActivityItem> findByGroupId(UUID groupId) {
		return jdbcTemplate.query("""
				SELECT type, id, description, payer_participant_id, recipient_participant_id,
				       amount_cents, created_at
				FROM (
				    SELECT 'EXPENSE' AS type, e.id, e.description,
				           e.paid_by_participant_id AS payer_participant_id,
				           NULL::uuid AS recipient_participant_id,
				           e.total_cents AS amount_cents, e.created_at
				    FROM expenses e
				    WHERE e.group_id = ?
				    UNION ALL
				    SELECT 'SETTLEMENT', s.id, NULL,
				           s.payer_participant_id, s.recipient_participant_id,
				           s.amount_cents, s.created_at
				    FROM settlements s
				    WHERE s.group_id = ? AND s.status = 'COMPLETED'
				) history
				ORDER BY created_at, id
				""", MAPPER, groupId, groupId);
	}

}
