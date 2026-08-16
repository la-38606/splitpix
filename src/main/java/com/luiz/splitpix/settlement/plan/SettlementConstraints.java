package com.luiz.splitpix.settlement.plan;

import java.util.Set;
import java.util.UUID;

/**
 * Request-time restrictions on the settlement graph. Forbidden pairs are
 * directed: forbidding A→B still allows B→A. {@code maxTransferCents} caps
 * the amount of any payer→recipient edge — a pair appears at most once in a
 * plan, so a capped debt is never split into installments; null means
 * uncapped.
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
