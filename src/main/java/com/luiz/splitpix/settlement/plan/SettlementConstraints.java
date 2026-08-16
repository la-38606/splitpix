package com.luiz.splitpix.settlement.plan;

import java.util.Set;
import java.util.UUID;

/**
 * Request-time restrictions on the settlement graph. Forbidden pairs are
 * directed: forbidding A→B still allows B→A. {@code maxTransferCents} caps
 * each individual transfer; null means uncapped.
 */
public record SettlementConstraints(
		Set<Pair> forbiddenPairs,
		Long maxTransferCents) {

	public record Pair(UUID payerParticipantId, UUID recipientParticipantId) {
	}

	public static final SettlementConstraints NONE = new SettlementConstraints(Set.of(), null);

	public boolean none() {
		return forbiddenPairs.isEmpty() && maxTransferCents == null;
	}

	public boolean allows(UUID payer, UUID recipient) {
		return !forbiddenPairs.contains(new Pair(payer, recipient));
	}

}
