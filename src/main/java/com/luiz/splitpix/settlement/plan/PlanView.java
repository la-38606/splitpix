package com.luiz.splitpix.settlement.plan;

import java.util.List;
import java.util.UUID;

/**
 * A settlement plan with names and Pix keys attached: what the API returns
 * and the pages render. {@code recipientPixKey} is null when the recipient
 * has none.
 */
public record PlanView(
		SettlementStrategy strategy,
		boolean exact,
		int transferCount,
		int novelRelationshipEdges,
		long totalAmountCents,
		List<TransferView> transfers) {

	public record TransferView(
			UUID payerParticipantId,
			String payerName,
			UUID recipientParticipantId,
			String recipientName,
			String recipientPixKey,
			long amountCents,
			boolean novelRelationship) {
	}

}
