package com.luiz.splitpix.settlement.plan;

import java.util.UUID;

/**
 * One suggested payment. {@code novelRelationship} is true when payer and
 * recipient have no prior financial relationship in this group (no shared
 * expense, no previous settlement between them).
 */
public record PlanTransfer(
		UUID payerParticipantId,
		UUID recipientParticipantId,
		long amountCents,
		boolean novelRelationship) {
}
