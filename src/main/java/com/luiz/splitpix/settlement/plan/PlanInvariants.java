package com.luiz.splitpix.settlement.plan;

import com.luiz.splitpix.balance.ParticipantBalance;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Machine-checked validity of a settlement plan. Runs in production on every
 * generated plan (cheap: O(participants + transfers)) so an optimizer bug
 * becomes a 500, never a payment suggestion that fails to settle the group.
 */
public final class PlanInvariants {

	private PlanInvariants() {
	}

	public static void verify(List<ParticipantBalance> balances, SettlementPlan plan,
			SettlementConstraints constraints) {
		Map<UUID, Long> remaining = new HashMap<>();
		for (ParticipantBalance balance : balances) {
			remaining.put(balance.participantId(), balance.balanceCents());
		}

		Set<SettlementConstraints.Pair> seenPairs = new HashSet<>();
		for (PlanTransfer transfer : plan.transfers()) {
			check(transfer.amountCents() > 0, "non-positive transfer amount");
			check(!transfer.payerParticipantId().equals(transfer.recipientParticipantId()),
					"participant pays themself");
			check(remaining.containsKey(transfer.payerParticipantId())
					&& remaining.containsKey(transfer.recipientParticipantId()),
					"transfer references a participant outside the balance set");
			check(constraints.allows(transfer.payerParticipantId(), transfer.recipientParticipantId()),
					"transfer uses a forbidden pair");
			check(constraints.maxTransferCents() == null
					|| transfer.amountCents() <= constraints.maxTransferCents(),
					"transfer exceeds the edge amount cap");
			check(seenPairs.add(new SettlementConstraints.Pair(
					transfer.payerParticipantId(), transfer.recipientParticipantId())),
					"duplicate payer-recipient pair");

			// A transfer raises the payer and lowers the recipient; applying
			// the whole plan must leave every balance at exactly zero.
			remaining.merge(transfer.payerParticipantId(), transfer.amountCents(), Long::sum);
			remaining.merge(transfer.recipientParticipantId(), -transfer.amountCents(), Long::sum);
		}

		for (Map.Entry<UUID, Long> entry : remaining.entrySet()) {
			check(entry.getValue() == 0, "plan leaves a nonzero balance");
		}

		long totalSent = plan.transfers().stream().mapToLong(PlanTransfer::amountCents).sum();
		long totalOwed = balances.stream().mapToLong(ParticipantBalance::balanceCents)
				.filter(cents -> cents > 0).sum();
		check(totalSent == totalOwed, "total sent differs from total owed");
	}

	private static void check(boolean condition, String violation) {
		if (!condition) {
			throw new IllegalStateException("settlement plan invariant violated: " + violation);
		}
	}

}
