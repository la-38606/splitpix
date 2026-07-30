package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luiz.splitpix.balance.DebtSimplifier;
import com.luiz.splitpix.balance.DebtSimplifier.Transfer;
import com.luiz.splitpix.balance.ParticipantBalance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain unit tests — no Spring, no database (design doc 18.1). */
class DebtSimplifierTest {

	private static ParticipantBalance balance(UUID id, long cents) {
		return new ParticipantBalance(id, "p", cents);
	}

	@Test
	void twoParties_singleExactTransfer() {
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		List<Transfer> transfers = DebtSimplifier.simplify(List.of(
				balance(alice, -5000), balance(bob, 5000)));

		assertThat(transfers).containsExactly(new Transfer(alice, bob, 5000));
	}

	@Test
	void designDocExample_fourTransfersAllToTheCreditor() {
		UUID luiz = UUID.randomUUID();
		UUID ana = UUID.randomUUID();
		UUID bruno = UUID.randomUUID();
		UUID clara = UUID.randomUUID();
		UUID diego = UUID.randomUUID();
		List<Transfer> transfers = DebtSimplifier.simplify(List.of(
				balance(luiz, 35000), balance(ana, -9000), balance(bruno, -8000),
				balance(clara, -6000), balance(diego, -12000)));

		assertThat(transfers).hasSize(4);
		assertThat(transfers).allMatch(t -> t.recipientParticipantId().equals(luiz));
		Map<UUID, Long> paid = new HashMap<>();
		transfers.forEach(t -> paid.merge(t.payerParticipantId(), t.amountCents(), Long::sum));
		assertThat(paid).containsEntry(ana, 9000L).containsEntry(bruno, 8000L)
				.containsEntry(clara, 6000L).containsEntry(diego, 12000L);
	}

	@Test
	void allZeroBalances_produceNoTransfers() {
		assertThat(DebtSimplifier.simplify(List.of(
				balance(UUID.randomUUID(), 0), balance(UUID.randomUUID(), 0)))).isEmpty();
	}

	@Test
	void nonZeroSum_isRejected() {
		assertThatThrownBy(() -> DebtSimplifier.simplify(List.of(balance(UUID.randomUUID(), 1))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void randomizedZeroSumBalances_alwaysSettleCompletely() {
		// Property test (design doc 18.5), deterministic via fixed seeds.
		for (int seed = 0; seed < 300; seed++) {
			Random random = new Random(seed);
			int participants = 2 + random.nextInt(9);

			List<ParticipantBalance> balances = new ArrayList<>();
			long sum = 0;
			for (int i = 0; i < participants - 1; i++) {
				long cents = random.nextLong(2_000_001) - 1_000_000;
				balances.add(balance(UUID.randomUUID(), cents));
				sum += cents;
			}
			balances.add(balance(UUID.randomUUID(), -sum));

			List<Transfer> transfers = DebtSimplifier.simplify(balances);

			Map<UUID, Long> remaining = new HashMap<>();
			balances.forEach(b -> remaining.put(b.participantId(), b.balanceCents()));
			long nonZero = balances.stream().filter(b -> b.balanceCents() != 0).count();

			for (Transfer transfer : transfers) {
				// Invariant 10: only positive amounts; never pay yourself.
				assertThat(transfer.amountCents()).as("seed %d", seed).isPositive();
				assertThat(transfer.payerParticipantId()).as("seed %d", seed)
						.isNotEqualTo(transfer.recipientParticipantId());
				remaining.merge(transfer.payerParticipantId(), transfer.amountCents(), Long::sum);
				remaining.merge(transfer.recipientParticipantId(), -transfer.amountCents(), Long::sum);
			}

			// Invariant 9: applying every suggestion reduces all balances to zero.
			assertThat(remaining.values()).as("seed %d", seed).allMatch(b -> b == 0L);
			// Greedy guarantee: at most n-1 transfers.
			assertThat(transfers.size()).as("seed %d", seed)
					.isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));
		}
	}

}
