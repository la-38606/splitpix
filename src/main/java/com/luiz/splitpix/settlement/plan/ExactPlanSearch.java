package com.luiz.splitpix.settlement.plan;

import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.common.ConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exhaustive search shared by MIN_TRANSFERS and RELATIONSHIP_AWARE.
 *
 * A move always transfers min(debt, credit, cap) between one debtor and one
 * creditor, so every move either zeroes a participant or saturates an edge.
 * That enumerates exactly the basic solutions of the (capacitated)
 * transportation problem, and any plan can be reduced to a basic one on a
 * subset of its edges without increasing transfers or novel relationships —
 * which is why searching only these plans still finds the true optimum
 * (docs/design.md section 10.4).
 *
 * Cost is the pair (novel edges, transfers), compared lexicographically;
 * MIN_TRANSFERS charges 0 for novelty so only the transfer count matters.
 * States are memoized on the remaining balance vector: the cost to finish
 * from a state does not depend on how the search got there. Ties keep the
 * first plan found, and pairs are visited in participant order, so the same
 * input always yields the same plan.
 */
final class ExactPlanSearch {

	/**
	 * Refusal thresholds, counted in nonzero balances. The search is
	 * exponential, and an edge amount cap makes it worse: a capped move can
	 * saturate an edge without zeroing anyone, so the memo key must include
	 * used edges and states repeat far less. Both bounds are measured, not
	 * guessed — see PlanSearchPropertyTest.worstCasesAtTheThreshold and
	 * docs/design.md section 10.6.
	 */
	static final int MAX_NONZERO_BALANCES = 10;
	static final int MAX_NONZERO_BALANCES_CAPPED = 8;

	/**
	 * Deterministic backstop: identical input explores identical states, so
	 * this either always trips for a given request or never does. No input
	 * within MAX_NONZERO_BALANCES has come close in testing.
	 */
	static final long NODE_BUDGET = 5_000_000L;

	record Move(int payer, int recipient, long amountCents) {
	}

	private record Best(int novel, int transfers, Move move) {
	}

	private static final Best INFEASIBLE = new Best(Integer.MAX_VALUE, Integer.MAX_VALUE, null);
	private static final Best SETTLED = new Best(0, 0, null);

	private final long[] balances;
	private final boolean[][] allowed;
	private final boolean[][] related;
	private final long capCents; // 0 = no cap
	private final boolean novelFirst;
	private final boolean[][] used;
	private final Map<String, Best> memo = new HashMap<>();
	private long nodes;

	private ExactPlanSearch(long[] balances, boolean[][] allowed, boolean[][] related,
			long capCents, boolean novelFirst) {
		this.balances = balances;
		this.allowed = allowed;
		this.related = related;
		this.capCents = capCents;
		this.novelFirst = novelFirst;
		this.used = new boolean[balances.length][balances.length];
	}

	/**
	 * @param balances nonzero balances in a deterministic order; must sum to zero
	 * @param allowed  allowed[payer][recipient], already excluding forbidden pairs
	 * @param related  existing-relationship matrix, symmetric
	 * @param capCents cap on the amount of any payer→recipient edge, 0 for none
	 * @param novelFirst true for RELATIONSHIP_AWARE's lexicographic objective
	 * @throws ConflictException with NO_FEASIBLE_SETTLEMENT_PLAN when the
	 *         constraints admit no plan
	 */
	static List<Move> minimize(long[] balances, boolean[][] allowed, boolean[][] related,
			long capCents, boolean novelFirst) {
		if (balances.length > (capCents > 0 ? MAX_NONZERO_BALANCES_CAPPED : MAX_NONZERO_BALANCES)) {
			throw new BadRequestException("UNSUPPORTED_OPTIMIZATION_SIZE");
		}
		ExactPlanSearch search = new ExactPlanSearch(balances.clone(), allowed, related, capCents, novelFirst);
		if (search.search() == INFEASIBLE) {
			throw new ConflictException("NO_FEASIBLE_SETTLEMENT_PLAN");
		}
		return search.reconstruct();
	}

	private Best search() {
		if (++nodes > NODE_BUDGET) {
			throw new BadRequestException("UNSUPPORTED_OPTIMIZATION_SIZE");
		}
		String key = stateKey();
		Best cached = memo.get(key);
		if (cached != null) {
			return cached;
		}
		Best best = allZero() ? SETTLED : INFEASIBLE;
		if (best == INFEASIBLE) {
			for (int i = 0; i < balances.length; i++) {
				if (balances[i] >= 0) {
					continue;
				}
				for (int j = 0; j < balances.length; j++) {
					if (balances[j] <= 0 || !allowed[i][j] || used[i][j]) {
						continue;
					}
					long amount = Math.min(-balances[i], balances[j]);
					if (capCents > 0) {
						amount = Math.min(amount, capCents);
					}
					balances[i] += amount;
					balances[j] -= amount;
					used[i][j] = true;
					Best sub = search();
					used[i][j] = false;
					balances[i] -= amount;
					balances[j] += amount;
					if (sub == INFEASIBLE) {
						continue;
					}
					int novel = sub.novel + (novelFirst && !related[i][j] ? 1 : 0);
					int transfers = sub.transfers + 1;
					if (novel < best.novel || (novel == best.novel && transfers < best.transfers)) {
						best = new Best(novel, transfers, new Move(i, j, amount));
					}
				}
			}
		}
		memo.put(key, best);
		return best;
	}

	/** Replays the memoized first moves from the initial state. */
	private List<Move> reconstruct() {
		List<Move> moves = new ArrayList<>();
		Best best = memo.get(stateKey());
		while (best.move() != null) {
			Move move = best.move();
			moves.add(move);
			balances[move.payer()] += move.amountCents();
			balances[move.recipient()] -= move.amountCents();
			used[move.payer()][move.recipient()] = true;
			best = memo.get(stateKey());
		}
		return moves;
	}

	private boolean allZero() {
		for (long balance : balances) {
			if (balance != 0) {
				return false;
			}
		}
		return true;
	}

	private String stateKey() {
		StringBuilder key = new StringBuilder();
		for (long balance : balances) {
			key.append(balance).append(',');
		}
		// A pair can only repeat when a cap saturates it short of zeroing
		// either side, so used-edge tracking is part of the state only then.
		if (capCents > 0) {
			for (boolean[] row : used) {
				for (boolean edge : row) {
					key.append(edge ? '1' : '0');
				}
			}
		}
		return key.toString();
	}

}
