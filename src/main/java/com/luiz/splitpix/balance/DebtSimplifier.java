package com.luiz.splitpix.balance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Greedy debt simplification (design doc section 12): repeatedly match the
 * largest debtor with the largest creditor. Guarantees at most n-1 transfers
 * and O(n log n); exact transfer-count minimization is NP-hard and out of
 * scope (section 28.9).
 *
 * Suggestions are derived from current balances and never stored (12.6).
 */
public final class DebtSimplifier {

	public record Transfer(UUID payerParticipantId, UUID recipientParticipantId, long amountCents) {
	}

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

	private DebtSimplifier() {
	}

	public static List<Transfer> simplify(List<ParticipantBalance> balances) {
		long sum = balances.stream().mapToLong(ParticipantBalance::balanceCents).sum();
		if (sum != 0) {
			throw new IllegalArgumentException("balances must sum to zero, got " + sum);
		}

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

		List<Transfer> transfers = new ArrayList<>();
		while (!debtors.isEmpty() && !creditors.isEmpty()) {
			Entry debtor = debtors.poll();
			Entry creditor = creditors.poll();

			long amount = Math.min(-debtor.balanceCents(), creditor.balanceCents());
			transfers.add(new Transfer(debtor.participantId(), creditor.participantId(), amount));

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
