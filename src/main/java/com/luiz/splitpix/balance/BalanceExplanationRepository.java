package com.luiz.splitpix.balance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BalanceExplanationRepository {

	private static final RowMapper<BalanceExplanation.Entry> MAPPER = (rs, rowNum) -> new BalanceExplanation.Entry(
			rs.getString("entry_type"),
			rs.getObject("source_id", UUID.class),
			rs.getString("description"),
			rs.getObject("counterparty_id", UUID.class),
			null, // resolved by the service, which holds the name snapshot
			rs.getLong("amount_cents"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	/**
	 * The same four legs as BalanceRepository.BALANCES_SQL, kept as rows
	 * instead of aggregated — the explanation and the balance describe the
	 * identical ledger truth by construction. Zero shares are omitted: they
	 * contribute nothing and would only pad the statement.
	 */
	private static final String EXPLANATION_SQL = """
			SELECT entry_type, source_id, description, counterparty_id, amount_cents, created_at
			FROM (
			    SELECT 'EXPENSE_PAID' AS entry_type, e.id AS source_id, e.description,
			           NULL::uuid AS counterparty_id, e.total_cents AS amount_cents, e.created_at
			    FROM expenses e
			    WHERE e.group_id = ? AND e.paid_by_participant_id = ?
			    UNION ALL
			    SELECT 'EXPENSE_SHARE', e.id, e.description, e.paid_by_participant_id,
			           -s.amount_cents, e.created_at
			    FROM expense_shares s
			    JOIN expenses e ON e.id = s.expense_id
			    WHERE s.group_id = ? AND s.participant_id = ? AND s.amount_cents > 0
			    UNION ALL
			    SELECT 'SETTLEMENT_SENT', st.id, NULL, st.recipient_participant_id,
			           st.amount_cents, st.created_at
			    FROM settlements st
			    WHERE st.group_id = ? AND st.payer_participant_id = ? AND st.status = 'COMPLETED'
			    UNION ALL
			    SELECT 'SETTLEMENT_RECEIVED', st.id, NULL, st.payer_participant_id,
			           -st.amount_cents, st.created_at
			    FROM settlements st
			    WHERE st.group_id = ? AND st.recipient_participant_id = ? AND st.status = 'COMPLETED'
			) legs
			ORDER BY created_at, source_id, entry_type
			""";

	private final JdbcTemplate jdbcTemplate;

	public BalanceExplanationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<BalanceExplanation.Entry> findEntries(UUID groupId, UUID participantId) {
		return jdbcTemplate.query(EXPLANATION_SQL, MAPPER,
				groupId, participantId, groupId, participantId,
				groupId, participantId, groupId, participantId);
	}

}
