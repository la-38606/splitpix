package com.luiz.splitpix.settlement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementRepository {

	/** The MVP stores only completed settlements (docs/design.md section 6.5). */
	public static final String STATUS_COMPLETED = "COMPLETED";

	private static final RowMapper<Settlement> MAPPER = (rs, rowNum) -> new Settlement(
			rs.getObject("id", UUID.class),
			rs.getObject("group_id", UUID.class),
			rs.getObject("payer_participant_id", UUID.class),
			rs.getObject("recipient_participant_id", UUID.class),
			rs.getLong("amount_cents"),
			rs.getString("idempotency_key"),
			rs.getString("request_hash"),
			rs.getString("status"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbcTemplate;

	public SettlementRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Returns the database-assigned creation timestamp. */
	public Instant insert(UUID id, UUID groupId, UUID payerParticipantId, UUID recipientParticipantId,
			long amountCents, String idempotencyKey, String requestHash) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO settlements
				    (id, group_id, payer_participant_id, recipient_participant_id, amount_cents,
				     idempotency_key, request_hash, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING created_at
				""",
				(rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				id, groupId, payerParticipantId, recipientParticipantId, amountCents, idempotencyKey,
				requestHash, STATUS_COMPLETED);
	}

	public Optional<Settlement> findByGroupIdAndIdempotencyKey(UUID groupId, String idempotencyKey) {
		return jdbcTemplate.query("""
				SELECT id, group_id, payer_participant_id, recipient_participant_id,
				       amount_cents, idempotency_key, request_hash, status, created_at
				FROM settlements
				WHERE group_id = ? AND idempotency_key = ?
				""", MAPPER, groupId, idempotencyKey)
				.stream().findFirst();
	}

}
