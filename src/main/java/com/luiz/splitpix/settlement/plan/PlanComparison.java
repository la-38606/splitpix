package com.luiz.splitpix.settlement.plan;

import java.util.List;
import java.util.UUID;

/**
 * Every strategy's plan for the same ledger snapshot, side by side. A
 * strategy the current group size rules out appears under {@code skipped}
 * with the error code it would have raised.
 */
public record PlanComparison(
		UUID groupId,
		long ledgerRevision,
		List<PlanView> plans,
		List<Skipped> skipped) {

	public record Skipped(SettlementStrategy strategy, String reason) {
	}

}
