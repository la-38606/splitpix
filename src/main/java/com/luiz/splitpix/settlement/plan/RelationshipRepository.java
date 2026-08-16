package com.luiz.splitpix.settlement.plan;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RelationshipRepository {

	private final JdbcTemplate jdbcTemplate;

	public RelationshipRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * The relationship rule (ADR 0010): paying an expense relates the payer to
	 * everyone holding a positive share of it, and a completed settlement
	 * relates its two ends. Zero shares create no edge — being listed on a
	 * split you owed nothing for is not a financial relationship.
	 */
	public RelationshipGraph findByGroupId(UUID groupId) {
		List<RelationshipGraph.Edge> edges = jdbcTemplate.query("""
				SELECT e.paid_by_participant_id AS a, s.participant_id AS b
				FROM expenses e
				JOIN expense_shares s ON s.expense_id = e.id
				WHERE e.group_id = ? AND s.amount_cents > 0
				  AND s.participant_id <> e.paid_by_participant_id
				UNION
				SELECT payer_participant_id, recipient_participant_id
				FROM settlements
				WHERE group_id = ? AND status = 'COMPLETED'
				""",
				(rs, rowNum) -> new RelationshipGraph.Edge(
						rs.getObject("a", UUID.class), rs.getObject("b", UUID.class)),
				groupId, groupId);
		return RelationshipGraph.of(edges);
	}

}
