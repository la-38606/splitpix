package com.luiz.splitpix.expense;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ExpenseRepository {

	private static final RowMapper<Expense> MAPPER = (rs, rowNum) -> new Expense(
			rs.getObject("id", UUID.class),
			rs.getObject("group_id", UUID.class),
			rs.getObject("paid_by_participant_id", UUID.class),
			rs.getString("description"),
			rs.getLong("total_cents"),
			rs.getString("idempotency_key"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<ExpenseShare> SHARE_MAPPER = (rs, rowNum) -> new ExpenseShare(
			rs.getObject("participant_id", UUID.class),
			rs.getLong("amount_cents"));

	private final JdbcTemplate jdbcTemplate;

	public ExpenseRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Returns the database-assigned creation timestamp. */
	public Instant insertExpense(UUID id, UUID groupId, UUID paidByParticipantId, String description,
			long totalCents, String idempotencyKey) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents, idempotency_key)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING created_at
				""",
				(rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				id, groupId, paidByParticipantId, description, totalCents, idempotencyKey);
	}

	public void insertShares(UUID expenseId, UUID groupId, List<ExpenseShare> shares) {
		jdbcTemplate.batchUpdate(
				"INSERT INTO expense_shares (expense_id, group_id, participant_id, amount_cents) VALUES (?, ?, ?, ?)",
				shares, shares.size(), (ps, share) -> {
					ps.setObject(1, expenseId);
					ps.setObject(2, groupId);
					ps.setObject(3, share.participantId());
					ps.setLong(4, share.amountCents());
				});
	}

	public Optional<Expense> findByGroupIdAndIdempotencyKey(UUID groupId, String idempotencyKey) {
		return jdbcTemplate.query("""
				SELECT id, group_id, paid_by_participant_id, description, total_cents, idempotency_key, created_at
				FROM expenses
				WHERE group_id = ? AND idempotency_key = ?
				""", MAPPER, groupId, idempotencyKey)
				.stream().findFirst();
	}

	public List<ExpenseShare> findShares(UUID expenseId) {
		return jdbcTemplate.query("""
				SELECT participant_id, amount_cents
				FROM expense_shares
				WHERE expense_id = ?
				ORDER BY participant_id
				""", SHARE_MAPPER, expenseId);
	}

}
