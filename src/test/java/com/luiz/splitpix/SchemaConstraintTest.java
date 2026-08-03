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
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents,
				                      idempotency_key, request_hash)
				VALUES (?, ?, ?, ?, ?, ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
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

	private void insertSettlement(UUID groupId, UUID payer, UUID recipient, long amountCents,
			String idempotencyKey, String status) {
		jdbcTemplate.update("""
				INSERT INTO settlements (id, group_id, payer_participant_id, recipient_participant_id,
				                         amount_cents, idempotency_key, request_hash, status)
				VALUES (?, ?, ?, ?, ?, ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', ?)
				""", UUID.randomUUID(), groupId, payer, recipient, amountCents, idempotencyKey, status);
	}

	@Test
	void negativeShareAmount_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID expenseId = insertExpense(groupId, payer, 100L, "k-negshare");
		assertThatThrownBy(() -> insertShare(expenseId, groupId, payer, -1L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shareAboveCap_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID expenseId = insertExpense(groupId, payer, 100L, "k-capshare");
		assertThatThrownBy(() -> insertShare(expenseId, groupId, payer, 1_000_000_000_001L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateExpenseIdempotencyKeyInGroup_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		insertExpense(groupId, payer, 100L, "same-key");
		assertThatThrownBy(() -> insertExpense(groupId, payer, 200L, "same-key"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameExpenseIdempotencyKeyInDifferentGroups_isAllowed() {
		UUID groupA = insertGroup();
		UUID groupB = insertGroup();
		insertExpense(groupA, insertParticipant(groupA, null), 100L, "cross-key");
		insertExpense(groupB, insertParticipant(groupB, null), 100L, "cross-key");
	}

	@Test
	void negativeSettlementAmount_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID recipient = insertParticipant(groupId, null);
		assertThatThrownBy(() -> insertSettlement(groupId, payer, recipient, -1L, "s-neg", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void settlementAboveCap_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID recipient = insertParticipant(groupId, null);
		assertThatThrownBy(() ->
				insertSettlement(groupId, payer, recipient, 1_000_000_000_001L, "s-cap", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void selfSettlement_isRejected() {
		UUID groupId = insertGroup();
		UUID participant = insertParticipant(groupId, null);
		assertThatThrownBy(() ->
				insertSettlement(groupId, participant, participant, 100L, "s-self", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void settlementParticipantsFromAnotherGroup_areRejected() {
		UUID groupA = insertGroup();
		UUID groupB = insertGroup();
		UUID insider = insertParticipant(groupA, null);
		UUID outsider = insertParticipant(groupB, null);

		assertThatThrownBy(() -> insertSettlement(groupA, outsider, insider, 100L, "s-x1", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertSettlement(groupA, insider, outsider, 100L, "s-x2", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void settlementStatusOtherThanCompleted_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID recipient = insertParticipant(groupId, null);
		assertThatThrownBy(() -> insertSettlement(groupId, payer, recipient, 100L, "s-status", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateSettlementIdempotencyKeyInGroup_isRejected() {
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID recipient = insertParticipant(groupId, null);
		insertSettlement(groupId, payer, recipient, 100L, "s-dup", "COMPLETED");
		assertThatThrownBy(() -> insertSettlement(groupId, payer, recipient, 200L, "s-dup", "COMPLETED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void requestHash_isMandatoryOnBothIdempotentTables() {
		// A NULL hash would NPE every replay of that key instead of replaying.
		UUID groupId = insertGroup();
		UUID payer = insertParticipant(groupId, null);
		UUID recipient = insertParticipant(groupId, null);

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO expenses (id, group_id, paid_by_participant_id, description, total_cents,
				                      idempotency_key, request_hash)
				VALUES (?, ?, ?, ?, ?, ?, NULL)
				""", UUID.randomUUID(), groupId, payer, "d", 100L, "k-nullhash"))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO settlements (id, group_id, payer_participant_id, recipient_participant_id,
				                         amount_cents, idempotency_key, request_hash, status)
				VALUES (?, ?, ?, ?, ?, ?, NULL, 'COMPLETED')
				""", UUID.randomUUID(), groupId, payer, recipient, 100L, "s-nullhash"))
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
		insertSettlement(groupId, other, payer, 200L, "k4", "COMPLETED");

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
