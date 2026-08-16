package com.luiz.splitpix.settlement.plan;

/**
 * How a settlement plan is computed. Internal names; the UI shows the pt-BR
 * labels from messages.properties.
 */
public enum SettlementStrategy {

	/**
	 * Largest debtor pays largest creditor, repeat. At most n-1 transfers,
	 * O(n log n), works at any group size. No optimality claim: see
	 * SettlementPlannerTest.greedyIsNotAlwaysMinimal for a five-person group
	 * where this produces four transfers and three suffice.
	 */
	GREEDY,

	/** Provably fewest transfers. Exhaustive search, so small groups only. */
	MIN_TRANSFERS,

	/**
	 * Fewest new payment relationships first, then fewest transfers. A plan
	 * may spend an extra transfer to avoid making two people who never shared
	 * an expense pay each other.
	 */
	RELATIONSHIP_AWARE

}
