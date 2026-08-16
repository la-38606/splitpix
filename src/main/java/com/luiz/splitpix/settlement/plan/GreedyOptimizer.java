package com.luiz.splitpix.settlement.plan;

import com.luiz.splitpix.balance.ParticipantBalance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Greedy simplification: repeatedly match the largest debtor with the largest
 * creditor. At most n-1 transfers, O(n log n), works at any group size. Makes
 * no optimality claim — MIN_TRANSFERS exists because this can be beaten (see
 * SettlementPlannerTest.greedyIsNotAlwaysMinimal).
 */
final class GreedyOptimizer {

	private record Entry(UUID participantId, long balanceCents) {
	}

	/** Most negative (largest debt) first; participant id as deterministic tiebreak. */
	private static final Comparator<Entry> DEBTOR_ORDER =
			Comparator.comparingLong(Entry::balanceCents)
					.thenComparing(e -> e.participantId().toString());

	/** Most positive (largest credit) first; same deterministic tiebreak. */
	private static final Comparator<Entry> CREDITOR_ORDER =
			Comparator.comparingLong(Entry::balanceCents).reversed()
					.thenComparing(e -> e.participantId().toString());

	private GreedyOptimizer() {
	}

	static List<PlanTransfer> simplify(List<ParticipantBalance> balances, RelationshipGraph relationships) {
		PriorityQueue<Entry> debtors = new PriorityQueue<>(DEBTOR_ORDER);
		PriorityQueue<Entry> creditors = new PriorityQueue<>(CREDITOR_ORDER);
		for (ParticipantBalance balance : balances) {
			if (balance.balanceCents() < 0) {
				debtors.add(new Entry(balance.participantId(), balance.balanceCents()));
			}
			else if (balance.balanceCents() > 0) {
				creditors.add(new Entry(balance.participantId(), balance.balanceCents()));
			}
		}

		List<PlanTransfer> transfers = new ArrayList<>();
		while (!debtors.isEmpty() && !creditors.isEmpty()) {
			Entry debtor = debtors.poll();
			Entry creditor = creditors.poll();

			long amount = Math.min(-debtor.balanceCents(), creditor.balanceCents());
			transfers.add(new PlanTransfer(debtor.participantId(), creditor.participantId(), amount,
					!relationships.related(debtor.participantId(), creditor.participantId())));

			long remainingDebt = debtor.balanceCents() + amount;
			long remainingCredit = creditor.balanceCents() - amount;
			if (remainingDebt < 0) {
				debtors.add(new Entry(debtor.participantId(), remainingDebt));
			}
			if (remainingCredit > 0) {
				creditors.add(new Entry(creditor.participantId(), remainingCredit));
			}
		}
		return transfers;
	}

}
