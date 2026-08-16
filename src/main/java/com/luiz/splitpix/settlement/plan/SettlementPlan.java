package com.luiz.splitpix.settlement.plan;

import java.util.List;

/**
 * A settlement plan and the claim it makes. {@code exact} means the plan is
 * provably optimal for the strategy's declared objective (transfer count for
 * MIN_TRANSFERS; novel relationships, then transfer count, for
 * RELATIONSHIP_AWARE). GREEDY plans are always {@code exact = false}: the
 * algorithm makes no optimality claim.
 */
public record SettlementPlan(
		SettlementStrategy strategy,
		boolean exact,
		List<PlanTransfer> transfers) {

	public int transferCount() {
		return transfers.size();
	}

	public int novelRelationshipEdges() {
		return (int) transfers.stream().filter(PlanTransfer::novelRelationship).count();
	}

	public long totalAmountCents() {
		return transfers.stream().mapToLong(PlanTransfer::amountCents).sum();
	}

}
