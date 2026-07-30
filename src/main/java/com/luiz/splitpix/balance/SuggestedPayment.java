package com.luiz.splitpix.balance;

import java.util.UUID;

/** Response shape of section 15.6. {@code recipientPixKey} is null when the recipient has none. */
public record SuggestedPayment(
		UUID payerParticipantId,
		String payerName,
		UUID recipientParticipantId,
		String recipientName,
		String recipientPixKey,
		long amountCents) {
}
