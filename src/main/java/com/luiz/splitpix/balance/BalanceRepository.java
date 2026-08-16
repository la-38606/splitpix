package com.luiz.splitpix.balance;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BalanceRepository {

	private static final RowMapper<ParticipantBalance> MAPPER = (rs, rowNum) -> new ParticipantBalance(
			rs.getObject("participant_id", UUID.class),
			rs.getString("display_name"),
			rs.getLong("balance_cents"));

	/**
	 * Balance derivation as one aggregate (design doc sections 11 and 28.1).
	 * Four legs via UNION ALL, per participant p:
	 *   + expenses paid by p        - shares assigned to p
	 *   + settlements sent by p     - settlements received by p
	 * Balances are derived, never stored; the sum over a group is always zero.
	 */
	private static final String BALANCES_SQL = """
			SELECT p.id AS participant_id,
			       p.display_name,
			       COALESCE(SUM(leg.amount_cents), 0) AS balance_cents
			FROM participants p
			LEFT JOIN (
			    SELECT paid_by_participant_id AS participant_id, total_cents AS amount_cents
			    FROM expenses
			    WHERE group_id = ?
			    UNION ALL
			    SELECT participant_id, -amount_cents
			    FROM expense_shares
			    WHERE group_id = ?
			    UNION ALL
			    SELECT payer_participant_id, amount_cents
			    FROM settlements
			    WHERE group_id = ? AND status = 'COMPLETED'
			    UNION ALL
			    SELECT recipient_participant_id, -amount_cents
			    FROM settlements
			    WHERE group_id = ? AND status = 'COMPLETED'
			) leg ON leg.participant_id = p.id
			WHERE p.group_id = ?
			GROUP BY p.id, p.display_name, p.created_at
			ORDER BY p.created_at, p.id
			""";

	private final JdbcTemplate jdbcTemplate;

	public BalanceRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<ParticipantBalance> computeBalances(UUID groupId) {
		return jdbcTemplate.query(BALANCES_SQL, MAPPER, groupId, groupId, groupId, groupId, groupId);
	}

}
