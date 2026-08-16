package com.luiz.splitpix.balance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where a balance comes from: every ledger entry that touched this
 * participant, in ledger order. The sum of {@code amountCents} over
 * {@code entries} equals {@code balanceCents} — enforced at runtime in
 * {@link BalanceService#explain}, not just in tests.
 *
 * Entry types mirror the four legs of the balance query: EXPENSE_PAID
 * (+total), EXPENSE_SHARE (-share), SETTLEMENT_SENT (+amount),
 * SETTLEMENT_RECEIVED (-amount).
 */
public record BalanceExplanation(
		UUID groupId,
		UUID participantId,
		String displayName,
		long balanceCents,
		List<Entry> entries) {

	public record Entry(
			String type,
			UUID sourceId,
			String description,
			UUID counterpartyParticipantId,
			String counterpartyName,
			long amountCents,
			Instant createdAt) {
	}

}
