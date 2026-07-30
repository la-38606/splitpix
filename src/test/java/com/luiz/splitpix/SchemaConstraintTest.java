package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the database constraints as a real second line of defense (section 3.2):
 * each test violates a constraint directly, below the service layer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaConstraintTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID insertGroup() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO groups (id, name, invite_token) VALUES (?, ?, ?)",
				id, "g", UUID.randomUUID().toString());
		return id;
	}

	private UUID insertParticipant(UUID groupId, String pixValue) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO participants (id, group_id, display_name, pix_key_type, pix_key_value)
				VALUES (?, ?, ?, ?, ?)
				""", id, groupId, "p", pixValue == null ? null : "EMAIL", pixValue);
		return id;
	}

	private UUID insertExpense(UUID groupId, UUID payer, long totalCents, String idempotencyKey) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents, idempotency_key)
				VALUES (?, ?, ?, ?, ?, ?)
				""", id, groupId, payer, "dinner", totalCents, idempotencyKey);
		return id;
	}

	private void insertShare(UUID expenseId, UUID groupId, UUID participantId, long amountCents) {
		jdbcTemplate.update("""
				INSERT INTO expense_shares (expense_id, group_id, participant_id, amount_cents)
				VALUES (?, ?, ?, ?)
				""", expenseId, groupId, participantId, amountCents);
	}

	@Test
	void duplicatePixKeyInGroup_isRejected() {
		UUID groupId = insertGroup();
		insertParticipant(groupId, "a@x.com");
		assertThatThrownBy(() -> insertParticipant(groupId, "a@x.com"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void multipleParticipantsWithoutPixKey_areAllowed() {
		UUID groupId = insertGroup();
		insertParticipant(groupId, null);
		insertParticipant(groupId, null);
	}

	@Test
	void pixTypeWithoutValue_isRejected() {
		UUID groupId = insertGroup();
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO participants (id, group_id, display_name, pix_key_type)
				VALUES (?, ?, ?, ?)
				""", UUID.randomUUID(), groupId, "p", "EMAIL"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownPixKeyType_isRejected() {
		UUID groupId = insertGroup();
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO participants (id, group_id, display_name, pix_key_type, pix_key_value)
				VALUES (?, ?, ?, ?, ?)
				""", UUID.randomUUID(), groupId, "p", "CPF", "123"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void expensePayerFromAnotherGroup_isRejected() {
		UUID groupA = insertGroup();
		UUID groupB = insertGroup();
		UUID outsider = insertParticipant(groupB, null);
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents, idempotency_key)
				VALUES (?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), groupA, outsider, "d", 100L, "k1"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shareForParticipantOfAnotherGroup_isRejected() {
		UUID groupA = insertGroup();
		UUID groupB = insertGroup();
		UUID payer = insertParticipant(groupA, null);
		UUID outsider = insertParticipant(groupB, null);
		UUID expenseId = insertExpense(groupA, payer, 100L, "k-cross");

		assertThatThrownBy(() -> insertShare(expenseId, groupA, outsider, 100L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingParticipantWithShares_isRejected() {
		// The deferred participant FK: group deletion cascades cleanly, but a
		// lone participant delete would silently break zero-sum — so it fails.
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID expenseId = insertExpense(groupId, payer, 100L, "k-del");
		insertShare(expenseId, groupId, payer, 100L);

		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM participants WHERE id = ?", payer))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void expenseTotalAboveCap_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		assertThatThrownBy(() -> insertExpense(groupId, payer, 1_000_000_000_001L, "k-cap"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void nonPositiveExpenseTotal_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents, idempotency_key)
				VALUES (?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), groupId, payer, "d", 0L, "k2"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingGroup_cascadesThroughFullGraph() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID other = insertParticipant(groupId, null);

		UUID expenseId = insertExpense(groupId, payer, 300L, "k3");
		insertShare(expenseId, groupId, payer, 100L);
		insertShare(expenseId, groupId, other, 200L);
		jdbcTemplate.update("""
				INSERT INTO settlements (id, group_id, payer_participant_id, recipient_participant_id,
				                         amount_cents, idempotency_key, status)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), groupId, other, payer, 200L, "k4", "COMPLETED");

		jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);

		for (String table : new String[] { "participants", "expenses", "expense_shares", "settlements" }) {
			Integer count = table.equals("expense_shares")
					? jdbcTemplate.queryForObject(
							"SELECT COUNT(*) FROM expense_shares WHERE expense_id = ?", Integer.class, expenseId)
					: jdbcTemplate.queryForObject(
							"SELECT COUNT(*) FROM " + table + " WHERE group_id = ?", Integer.class, groupId);
			assertThat(count).as(table).isZero();
		}
	}

}
