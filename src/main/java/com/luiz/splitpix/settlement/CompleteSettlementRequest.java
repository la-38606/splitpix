package com.luiz.splitpix.settlement;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CompleteSettlementRequest(
		@NotNull UUID payerParticipantId,
		@NotNull UUID recipientParticipantId,
		@NotNull Long amountCents) {
}
