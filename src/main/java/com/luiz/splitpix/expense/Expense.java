package com.luiz.splitpix.expense;

import java.time.Instant;
import java.util.UUID;

public record Expense(
		UUID id,
		UUID groupId,
		UUID paidByParticipantId,
		String description,
		long totalCents,
		String idempotencyKey,
		Instant createdAt) {
}
