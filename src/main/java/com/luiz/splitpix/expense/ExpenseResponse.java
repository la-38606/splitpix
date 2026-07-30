package com.luiz.splitpix.expense;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
		UUID expenseId,
		String description,
		UUID paidByParticipantId,
		long totalCents,
		List<ShareResponse> shares,
		Instant createdAt) {

	public static ExpenseResponse from(Expense expense, List<ExpenseShare> shares) {
		return new ExpenseResponse(
				expense.id(),
				expense.description(),
				expense.paidByParticipantId(),
				expense.totalCents(),
				shares.stream().map(s -> new ShareResponse(s.participantId(), s.amountCents())).toList(),
				expense.createdAt());
	}

	public record ShareResponse(UUID participantId, long amountCents) {
	}

}
