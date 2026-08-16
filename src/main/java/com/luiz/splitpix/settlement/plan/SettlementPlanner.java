package com.luiz.splitpix.settlement.plan;

import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.common.BadRequestException;
import java.util.List;
import java.util.UUID;

/**
 * Strategy dispatch for settlement planning. Pure: balances in, plan out,
 * nothing touches the database here. Every returned plan has passed
 * {@link PlanInvariants#verify}, so a buggy optimizer fails loudly instead of
 * suggesting payments that do not settle the group.
 */
public final class SettlementPlanner {

	private SettlementPlanner() {
	}

	/**
	 * @param balances every participant's balance, in a deterministic order;
	 *                 must sum to zero
	 * @throws BadRequestException UNSUPPORTED_OPTIMIZATION_SIZE when an exact
	 *         strategy is asked for more nonzero balances than the search
	 *         supports, or INVALID_SETTLEMENT_CONSTRAINT when constraints are
	 *         combined with GREEDY
	 */
	public static SettlementPlan plan(SettlementStrategy strategy, List<ParticipantBalance> balances,
			RelationshipGraph relationships, SettlementConstraints constraints) {
		long sum = balances.stream().mapToLong(ParticipantBalance::balanceCents).sum();
		if (sum != 0) {
			throw new IllegalArgumentException("balances must sum to zero, got " + sum);
		}
		List<ParticipantBalance> active = balances.stream()
				.filter(b -> b.balanceCents() != 0)
				.toList();

		List<PlanTransfer> transfers = switch (strategy) {
			case GREEDY -> greedy(active, relationships, constraints);
			case MIN_TRANSFERS -> exact(active, relationships, constraints, false);
			case RELATIONSHIP_AWARE -> exact(active, relationships, constraints, true);
		};

		SettlementPlan plan = new SettlementPlan(strategy, strategy != SettlementStrategy.GREEDY, transfers);
		PlanInvariants.verify(balances, plan, constraints);
		return plan;
	}

	/** True when the exact strategies can run for this many nonzero balances. */
	public static boolean supportsExact(List<ParticipantBalance> balances) {
		return balances.stream().filter(b -> b.balanceCents() != 0).count() <= ExactPlanSearch.MAX_NONZERO_BALANCES;
	}

	private static List<PlanTransfer> greedy(List<ParticipantBalance> active,
			RelationshipGraph relationships, SettlementConstraints constraints) {
		// Greedy cannot honor constraints without losing its "always works"
		// guarantee, so the combination is rejected rather than silently
		// producing a plan that violates them (ADR 0011).
		if (!constraints.none()) {
			throw new BadRequestException("INVALID_SETTLEMENT_CONSTRAINT");
		}
		return GreedyOptimizer.simplify(active, relationships);
	}

	private static List<PlanTransfer> exact(List<ParticipantBalance> active,
			RelationshipGraph relationships, SettlementConstraints constraints, boolean novelFirst) {
		int n = active.size();
		long[] cents = new long[n];
		boolean[][] allowed = new boolean[n][n];
		boolean[][] related = new boolean[n][n];
		for (int i = 0; i < n; i++) {
			cents[i] = active.get(i).balanceCents();
			for (int j = 0; j < n; j++) {
				UUID payer = active.get(i).participantId();
				UUID recipient = active.get(j).participantId();
				allowed[i][j] = i != j && constraints.allows(payer, recipient);
				related[i][j] = relationships.related(payer, recipient);
			}
		}
		long cap = constraints.maxTransferCents() == null ? 0 : constraints.maxTransferCents();

		return ExactPlanSearch.minimize(cents, allowed, related, cap, novelFirst).stream()
				.map(move -> {
					UUID payer = active.get(move.payer()).participantId();
					UUID recipient = active.get(move.recipient()).participantId();
					return new PlanTransfer(payer, recipient, move.amountCents(),
							!relationships.related(payer, recipient));
				})
				.toList();
	}

}
