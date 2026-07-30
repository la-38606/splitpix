package com.luiz.splitpix.settlement;

import java.time.Instant;
import java.util.UUID;

public record SettlementResponse(
		UUID settlementId,
		UUID payerParticipantId,
		UUID recipientParticipantId,
		long amountCents,
		String status,
		Instant createdAt) {

	public static SettlementResponse from(Settlement settlement) {
		return new SettlementResponse(
				settlement.id(),
				settlement.payerParticipantId(),
				settlement.recipientParticipantId(),
				settlement.amountCents(),
				settlement.status(),
				settlement.createdAt());
	}

}
