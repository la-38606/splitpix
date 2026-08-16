package com.luiz.splitpix.settlement.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.common.ConflictException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Randomized cross-checks of the settlement optimizers, deterministic via
 * fixed seeds. The exact search is validated two independent ways: against
 * the closed-form minimum (n minus the largest zero-sum partition, computed
 * here by a subset DP that shares no code with the solver) and against
 * exhaustive enumeration of the same plan space without memoization.
 */
class PlanSearchPropertyTest {

	@Test
	void minTransfers_matchesIndependentPartitionBound() {
		for (int seed = 0; seed < 300; seed++) {
			Random random = new Random(seed);
			long[] cents = zeroSumVector(random, 2 + random.nextInt(7), 100_000);
			if (cents == null) {
				continue;
			}
			int n = cents.length;

			List<ExactPlanSearch.Move> moves = ExactPlanSearch.minimize(
					cents.clone(), allowAll(n), new boolean[n][n], 0, false);

			assertThat(moves.size())
					.as("seed %d, balances %s", seed, Arrays.toString(cents))
					.isEqualTo(n - maxZeroSumParts(cents));
		}
	}

	@Test
	void search_matchesExhaustiveEnumeration() {
		// Small instances with random relationships, forbidden pairs and caps.
		// The enumeration walks the identical move space with no memo and no
		// reconstruction, so it exercises everything the solver adds on top.
		for (int seed = 0; seed < 250; seed++) {
			Random random = new Random(seed);
			int n = 2 + random.nextInt(4);
			long[] cents = zeroSumVector(random, n, 1_000);
			if (cents == null) {
				continue;
			}
			boolean[][] allowed = new boolean[n][n];
			boolean[][] related = new boolean[n][n];
			for (int i = 0; i < n; i++) {
				for (int j = 0; j < n; j++) {
					allowed[i][j] = i != j && random.nextDouble() > 0.15;
					if (i < j) {
						related[i][j] = related[j][i] = random.nextBoolean();
					}
				}
			}
			long cap = random.nextBoolean() ? 0 : 1 + random.nextLong(1_500);
			boolean novelFirst = random.nextBoolean();

			int[] expected = exhaustiveBest(cents, allowed, related, cap, novelFirst);
			if (expected == null) {
				assertThatThrownBy(() -> ExactPlanSearch.minimize(cents.clone(), allowed, related, cap, novelFirst))
						.as("seed %d", seed)
						.isInstanceOf(ConflictException.class);
				continue;
			}

			List<ExactPlanSearch.Move> moves = ExactPlanSearch.minimize(
					cents.clone(), allowed, related, cap, novelFirst);
			int novel = (int) moves.stream().filter(m -> !related[m.payer()][m.recipient()]).count();
			if (novelFirst) {
				assertThat(new int[] { novel, moves.size() })
						.as("seed %d, balances %s", seed, Arrays.toString(cents))
						.isEqualTo(expected);
			}
			else {
				assertThat(moves.size())
						.as("seed %d, balances %s", seed, Arrays.toString(cents))
						.isEqualTo(expected[1]);
			}
		}
	}

	@Test
	void everyStrategy_settlesEveryRandomGroup() {
		for (int seed = 0; seed < 300; seed++) {
			Random random = new Random(seed);
			int n = 2 + random.nextInt(9);
			long[] cents = zeroSumVector(random, n, 1_000_000);
			if (cents == null) {
				continue;
			}
			List<ParticipantBalance> balances = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				balances.add(new ParticipantBalance(new UUID(seed, i), "p" + i, cents[i]));
			}

			SettlementPlan greedy = SettlementPlanner.plan(SettlementStrategy.GREEDY,
					balances, RelationshipGraph.EMPTY, SettlementConstraints.NONE);
			SettlementPlan fewest = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
					balances, RelationshipGraph.EMPTY, SettlementConstraints.NONE);
			SettlementPlan aware = SettlementPlanner.plan(SettlementStrategy.RELATIONSHIP_AWARE,
					balances, randomRelationships(random, balances), SettlementConstraints.NONE);

			for (SettlementPlan plan : List.of(greedy, fewest, aware)) {
				// Independent re-application, not PlanInvariants: a bug in the
				// shared checker must not hide a bug in the optimizers.
				Map<UUID, Long> remaining = new HashMap<>();
				balances.forEach(b -> remaining.put(b.participantId(), b.balanceCents()));
				for (PlanTransfer transfer : plan.transfers()) {
					assertThat(transfer.amountCents()).as("seed %d", seed).isPositive();
					remaining.merge(transfer.payerParticipantId(), transfer.amountCents(), Long::sum);
					remaining.merge(transfer.recipientParticipantId(), -transfer.amountCents(), Long::sum);
				}
				assertThat(remaining.values()).as("seed %d", seed).allMatch(b -> b == 0L);
				assertThat(plan.transferCount()).as("seed %d", seed)
						.isLessThanOrEqualTo((int) Math.max(0, nonzero(cents) - 1));
			}

			assertThat(fewest.transferCount()).as("seed %d", seed)
					.isLessThanOrEqualTo(greedy.transferCount());
		}
	}

	@Test
	void relationshipAware_neverUsesMoreNovelEdgesThanTheOthers() {
		for (int seed = 0; seed < 200; seed++) {
			Random random = new Random(seed);
			int n = 2 + random.nextInt(7);
			long[] cents = zeroSumVector(random, n, 100_000);
			if (cents == null) {
				continue;
			}
			List<ParticipantBalance> balances = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				balances.add(new ParticipantBalance(new UUID(seed, i), "p" + i, cents[i]));
			}
			RelationshipGraph graph = randomRelationships(random, balances);

			int aware = SettlementPlanner.plan(SettlementStrategy.RELATIONSHIP_AWARE,
					balances, graph, SettlementConstraints.NONE).novelRelationshipEdges();
			int fewest = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
					balances, graph, SettlementConstraints.NONE).novelRelationshipEdges();
			int greedy = SettlementPlanner.plan(SettlementStrategy.GREEDY,
					balances, graph, SettlementConstraints.NONE).novelRelationshipEdges();

			assertThat(aware).as("seed %d", seed)
					.isLessThanOrEqualTo(Math.min(fewest, greedy));
		}
	}

	@Test
	void worstCasesAtTheThreshold_stayWithinTheNodeBudget() {
		// Deterministic guard for the documented size thresholds: adversarial
		// instances at the boundary must finish without tripping NODE_BUDGET.
		// (Worst measured: 59 ms uncapped, 136 ms capped, over 200 seeds.)
		for (int seed = 0; seed < 10; seed++) {
			Random random = new Random(seed);
			long[] uncapped = zeroSumVector(random, ExactPlanSearch.MAX_NONZERO_BALANCES, 200_000);
			if (uncapped != null) {
				int n = uncapped.length;
				ExactPlanSearch.minimize(uncapped.clone(), allowAll(n), new boolean[n][n], 0, true);
				ExactPlanSearch.minimize(uncapped.clone(), allowAll(n), new boolean[n][n], 0, false);
			}
			long[] capped = zeroSumVector(random, ExactPlanSearch.MAX_NONZERO_BALANCES_CAPPED, 200_000);
			if (capped != null) {
				int n = capped.length;
				try {
					ExactPlanSearch.minimize(capped.clone(), allowAll(n), new boolean[n][n], 90_000, true);
				}
				catch (ConflictException e) {
					// Infeasible under the cap is a legitimate outcome here;
					// the point is that the search terminated inside budget.
				}
			}
		}
	}

	// ------------------------------------------------------------ generators

	/** n nonzero entries summing to zero, or null when the seed cannot deliver one. */
	private static long[] zeroSumVector(Random random, int n, long magnitude) {
		long[] cents = new long[n];
		long sum = 0;
		for (int i = 0; i < n - 1; i++) {
			long v;
			do {
				v = random.nextLong(2 * magnitude + 1) - magnitude;
			} while (v == 0);
			cents[i] = v;
			sum += v;
		}
		cents[n - 1] = -sum;
		return cents[n - 1] == 0 ? null : cents;
	}

	private static RelationshipGraph randomRelationships(Random random, List<ParticipantBalance> balances) {
		List<RelationshipGraph.Edge> edges = new ArrayList<>();
		for (int i = 0; i < balances.size(); i++) {
			for (int j = i + 1; j < balances.size(); j++) {
				if (random.nextBoolean()) {
					edges.add(new RelationshipGraph.Edge(
							balances.get(i).participantId(), balances.get(j).participantId()));
				}
			}
		}
		return RelationshipGraph.of(edges);
	}

	private static boolean[][] allowAll(int n) {
		boolean[][] allowed = new boolean[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				allowed[i][j] = i != j;
			}
		}
		return allowed;
	}

	private static int nonzero(long[] cents) {
		return (int) Arrays.stream(cents).filter(c -> c != 0).count();
	}

	// ---------------------------------------------------- independent oracles

	/**
	 * Largest number of disjoint zero-sum parts covering all balances. The
	 * true minimum transfer count is n minus this: every transfer graph
	 * component must be zero-sum and a component of size k needs k-1
	 * transfers.
	 */
	private static int maxZeroSumParts(long[] cents) {
		int n = cents.length;
		long[] sums = new long[1 << n];
		for (int mask = 1; mask < (1 << n); mask++) {
			int low = Integer.numberOfTrailingZeros(mask);
			sums[mask] = sums[mask & (mask - 1)] + cents[low];
		}
		int[] parts = new int[1 << n];
		Arrays.fill(parts, Integer.MIN_VALUE);
		parts[0] = 0;
		for (int mask = 1; mask < (1 << n); mask++) {
			if (sums[mask] != 0) {
				continue;
			}
			int low = 1 << Integer.numberOfTrailingZeros(mask);
			for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
				if ((sub & low) == 0 || sums[sub] != 0 || parts[mask ^ sub] == Integer.MIN_VALUE) {
					continue;
				}
				parts[mask] = Math.max(parts[mask], 1 + parts[mask ^ sub]);
			}
		}
		return parts[(1 << n) - 1];
	}

	/** Best {novel, transfers} over the full move space, or null if infeasible. */
	private static int[] exhaustiveBest(long[] cents, boolean[][] allowed, boolean[][] related,
			long cap, boolean novelFirst) {
		return exhaust(cents.clone(), new boolean[cents.length][cents.length],
				allowed, related, cap, novelFirst);
	}

	private static int[] exhaust(long[] cents, boolean[][] used, boolean[][] allowed,
			boolean[][] related, long cap, boolean novelFirst) {
		if (Arrays.stream(cents).allMatch(c -> c == 0)) {
			return new int[] { 0, 0 };
		}
		int[] best = null;
		for (int i = 0; i < cents.length; i++) {
			if (cents[i] >= 0) {
				continue;
			}
			for (int j = 0; j < cents.length; j++) {
				if (cents[j] <= 0 || !allowed[i][j] || used[i][j]) {
					continue;
				}
				long amount = Math.min(-cents[i], cents[j]);
				if (cap > 0) {
					amount = Math.min(amount, cap);
				}
				cents[i] += amount;
				cents[j] -= amount;
				used[i][j] = true;
				int[] sub = exhaust(cents, used, allowed, related, cap, novelFirst);
				used[i][j] = false;
				cents[i] -= amount;
				cents[j] += amount;
				if (sub == null) {
					continue;
				}
				int novel = sub[0] + (novelFirst && !related[i][j] ? 1 : 0);
				int transfers = sub[1] + 1;
				if (best == null || novel < best[0] || (novel == best[0] && transfers < best[1])) {
					best = new int[] { novel, transfers };
				}
			}
		}
		return best;
	}

}
