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
			rs.getLong("sequence"),
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

	/**
	 * The ledger in serialization order. Writes hold the group lock and
	 * timestamp with clock_timestamp(), so (created_at, id) reproduces the
	 * order entries actually committed in; ROW_NUMBER over that order gives
	 * each entry a stable sequence number.
	 */
	public List<ActivityItem> findByGroupId(UUID groupId) {
		return jdbcTemplate.query("""
				SELECT ROW_NUMBER() OVER (ORDER BY created_at, id) AS sequence,
				       type, id, description, payer_participant_id, recipient_participant_id,
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

	/**
	 * How many entries the ledger holds: expenses plus completed settlements.
	 * Append-only, so this only grows — equal revisions mean identical
	 * accounting state, and a plan stamped with a revision is stale once the
	 * group's revision moves past it.
	 */
	public long ledgerRevision(UUID groupId) {
		Long revision = jdbcTemplate.queryForObject("""
				SELECT (SELECT COUNT(*) FROM expenses WHERE group_id = ?)
				     + (SELECT COUNT(*) FROM settlements WHERE group_id = ? AND status = 'COMPLETED')
				""", Long.class, groupId, groupId);
		return revision == null ? 0 : revision;
	}

}
