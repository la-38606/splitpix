package com.luiz.splitpix.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the append-only ledger, in serialization order. For an
 * EXPENSE, {@code payerParticipantId} is who paid and
 * {@code recipientParticipantId} is null; for a SETTLEMENT both ends are set
 * and {@code description} is null. {@code sequence} is 1-based and dense: the
 * highest sequence equals the group's ledger revision.
 */
public record ActivityItem(
		long sequence,
		String type,
		UUID id,
		String description,
		UUID payerParticipantId,
		UUID recipientParticipantId,
		long amountCents,
		Instant createdAt) {
}
