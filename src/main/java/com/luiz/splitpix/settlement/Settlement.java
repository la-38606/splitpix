package com.luiz.splitpix.settlement;

import java.time.Instant;
import java.util.UUID;

public record Settlement(
		UUID id,
		UUID groupId,
		UUID payerParticipantId,
		UUID recipientParticipantId,
		long amountCents,
		String idempotencyKey,
		String requestHash,
		String status,
		Instant createdAt) {
}
