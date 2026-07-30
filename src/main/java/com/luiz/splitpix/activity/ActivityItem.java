package com.luiz.splitpix.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the combined history (design doc 15.8). For an EXPENSE,
 * {@code payerParticipantId} is who paid and {@code recipientParticipantId}
 * is null; for a SETTLEMENT both ends are set and {@code description} is null.
 */
public record ActivityItem(
		String type,
		UUID id,
		String description,
		UUID payerParticipantId,
		UUID recipientParticipantId,
		long amountCents,
		Instant createdAt) {
}
