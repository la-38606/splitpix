package com.luiz.splitpix.settlement.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.common.ApiException;
import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.common.ConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain unit tests — no Spring, no database. */
class SettlementPlannerTest {

	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private static final UUID C = new UUID(0, 3);
	private static final UUID D = new UUID(0, 4);

	private static ParticipantBalance balance(UUID id, long cents) {
		return new ParticipantBalance(id, "p", cents);
	}

	private static SettlementPlan plan(SettlementStrategy strategy, List<ParticipantBalance> balances) {
		return SettlementPlanner.plan(strategy, balances, RelationshipGraph.EMPTY, SettlementConstraints.NONE);
	}

	private static RelationshipGraph related(UUID[]... pairs) {
		List<RelationshipGraph.Edge> edges = new ArrayList<>();
		for (UUID[] pair : pairs) {
			edges.add(new RelationshipGraph.Edge(pair[0], pair[1]));
		}
		return RelationshipGraph.of(edges);
	}

	@Test
	void twoParties_singleExactTransfer_everyStrategy() {
		for (SettlementStrategy strategy : SettlementStrategy.values()) {
			SettlementPlan plan = plan(strategy, List.of(balance(A, -5000), balance(B, 5000)));
			assertThat(plan.transfers())
					.containsExactly(new PlanTransfer(A, B, 5000, true));
		}
	}

	@Test
	void designDocExample_fourTransfersAllToTheCreditor() {
		SettlementPlan plan = plan(SettlementStrategy.GREEDY, List.of(
				balance(A, 35000), balance(B, -9000), balance(C, -8000),
				balance(D, -6000), balance(new UUID(0, 5), -12000)));

		assertThat(plan.transferCount()).isEqualTo(4);
		assertThat(plan.transfers()).allMatch(t -> t.recipientParticipantId().equals(A));
		Map<UUID, Long> paid = new HashMap<>();
		plan.transfers().forEach(t -> paid.merge(t.payerParticipantId(), t.amountCents(), Long::sum));
		assertThat(paid).containsEntry(B, 9000L).containsEntry(C, 8000L).containsEntry(D, 6000L);
	}

	@Test
	void greedyIsNotAlwaysMinimal() {
		// The case that justifies MIN_TRANSFERS existing at all: greedy pairs
		// -400 with +500 and never isolates the {+400, -400} component, so it
		// needs four transfers where three settle the group.
		List<ParticipantBalance> balances = List.of(
				balance(A, 50000), balance(B, 40000), balance(C, -40000),
				balance(D, -30000), balance(new UUID(0, 5), -20000));

		assertThat(plan(SettlementStrategy.GREEDY, balances).transferCount()).isEqualTo(4);

		SettlementPlan exact = plan(SettlementStrategy.MIN_TRANSFERS, balances);
		assertThat(exact.transferCount()).isEqualTo(3);
		assertThat(exact.exact()).isTrue();
	}

	@Test
	void minTransfers_isDeterministic() {
		List<ParticipantBalance> balances = List.of(
				balance(A, 9000), balance(B, -3000), balance(C, -3000), balance(D, -3000));
		assertThat(plan(SettlementStrategy.MIN_TRANSFERS, balances).transfers())
				.containsExactlyElementsOf(plan(SettlementStrategy.MIN_TRANSFERS, balances).transfers());
	}

	@Test
	void relationshipAware_spendsATransferToAvoidANovelEdge() {
		// Related pairs: A-D and B-C only. Two transfers settle the group but
		// need two novel edges; one extra transfer brings that down to one,
		// and one is provably the floor (related capacity alone is 8000 of
		// the 10000 owed).
		List<ParticipantBalance> balances = List.of(
				balance(A, -6000), balance(B, -4000), balance(C, 6000), balance(D, 4000));
		RelationshipGraph graph = related(new UUID[] { A, D }, new UUID[] { B, C });

		SettlementPlan fewest = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				balances, graph, SettlementConstraints.NONE);
		assertThat(fewest.transferCount()).isEqualTo(2);
		assertThat(fewest.novelRelationshipEdges()).isEqualTo(2);

		SettlementPlan aware = SettlementPlanner.plan(SettlementStrategy.RELATIONSHIP_AWARE,
				balances, graph, SettlementConstraints.NONE);
		assertThat(aware.transferCount()).isEqualTo(3);
		assertThat(aware.novelRelationshipEdges()).isEqualTo(1);
		assertThat(aware.exact()).isTrue();
		assertThat(aware.transfers()).containsExactlyInAnyOrder(
				new PlanTransfer(A, D, 4000, false),
				new PlanTransfer(A, C, 2000, true),
				new PlanTransfer(B, C, 4000, false));
	}

	@Test
	void relationshipAware_reusesExistingEdgesWhenTheySuffice() {
		// One payer covered everything, so everyone is related to the hub and
		// no transfer creates a new relationship.
		List<ParticipantBalance> balances = List.of(
				balance(A, 9000), balance(B, -3000), balance(C, -3000), balance(D, -3000));
		RelationshipGraph graph = related(new UUID[] { A, B }, new UUID[] { A, C }, new UUID[] { A, D });

		SettlementPlan plan = SettlementPlanner.plan(SettlementStrategy.RELATIONSHIP_AWARE,
				balances, graph, SettlementConstraints.NONE);
		assertThat(plan.transferCount()).isEqualTo(3);
		assertThat(plan.novelRelationshipEdges()).isZero();
	}

	@Test
	void forbiddenPair_isRespected() {
		List<ParticipantBalance> balances = List.of(
				balance(A, -3000), balance(B, -3000), balance(C, 3000), balance(D, 3000));
		SettlementConstraints constraints = new SettlementConstraints(
				Set.of(new SettlementConstraints.Pair(A, C)), null);

		SettlementPlan plan = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				balances, RelationshipGraph.EMPTY, constraints);
		assertThat(plan.transfers()).containsExactlyInAnyOrder(
				new PlanTransfer(A, D, 3000, true),
				new PlanTransfer(B, C, 3000, true));
	}

	@Test
	void forbiddenPair_isDirected() {
		// Forbidding B→A does not forbid A→B.
		SettlementConstraints constraints = new SettlementConstraints(
				Set.of(new SettlementConstraints.Pair(B, A)), null);
		SettlementPlan plan = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				List.of(balance(A, -5000), balance(B, 5000)), RelationshipGraph.EMPTY, constraints);
		assertThat(plan.transfers()).containsExactly(new PlanTransfer(A, B, 5000, true));
	}

	@Test
	void impossibleConstraints_reportInfeasibility() {
		SettlementConstraints constraints = new SettlementConstraints(
				Set.of(new SettlementConstraints.Pair(A, B)), null);
		assertThatThrownBy(() -> SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				List.of(balance(A, -5000), balance(B, 5000)), RelationshipGraph.EMPTY, constraints))
				.isInstanceOf(ConflictException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo("NO_FEASIBLE_SETTLEMENT_PLAN");
	}

	@Test
	void transferCap_splitsAcrossCreditors() {
		SettlementConstraints constraints = new SettlementConstraints(Set.of(), 6000L);
		SettlementPlan plan = SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				List.of(balance(A, -10000), balance(B, 6000), balance(C, 4000)),
				RelationshipGraph.EMPTY, constraints);
		assertThat(plan.transfers()).containsExactlyInAnyOrder(
				new PlanTransfer(A, B, 6000, true),
				new PlanTransfer(A, C, 4000, true));
	}

	@Test
	void transferCap_canMakeSettlementInfeasible() {
		// One instruction per payer-recipient pair, so a 6000 cap cannot move
		// 10000 between two people.
		SettlementConstraints constraints = new SettlementConstraints(Set.of(), 6000L);
		assertThatThrownBy(() -> SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS,
				List.of(balance(A, -10000), balance(B, 10000)), RelationshipGraph.EMPTY, constraints))
				.isInstanceOf(ConflictException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo("NO_FEASIBLE_SETTLEMENT_PLAN");
	}

	@Test
	void greedyWithConstraints_isRejected() {
		SettlementConstraints constraints = new SettlementConstraints(Set.of(), 6000L);
		assertThatThrownBy(() -> SettlementPlanner.plan(SettlementStrategy.GREEDY,
				List.of(balance(A, -5000), balance(B, 5000)), RelationshipGraph.EMPTY, constraints))
				.isInstanceOf(BadRequestException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo("INVALID_SETTLEMENT_CONSTRAINT");
	}

	@Test
	void exactStrategies_refuseOversizedGroups_greedyDoesNot() {
		List<ParticipantBalance> balances = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			balances.add(balance(new UUID(1, i), -100));
		}
		balances.add(balance(new UUID(2, 0), 1000));
		assertThat(balances).hasSize(11);

		assertThat(SettlementPlanner.supportsExact(balances)).isFalse();
		assertThatThrownBy(() -> plan(SettlementStrategy.MIN_TRANSFERS, balances))
				.isInstanceOf(BadRequestException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo("UNSUPPORTED_OPTIMIZATION_SIZE");
		assertThat(plan(SettlementStrategy.GREEDY, balances).transferCount()).isEqualTo(10);
	}

	@Test
	void cappedSearch_hasTheLowerThreshold() {
		// Nine nonzero balances: fine uncapped, refused with a cap.
		List<ParticipantBalance> balances = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			balances.add(balance(new UUID(1, i), -100));
		}
		balances.add(balance(new UUID(2, 0), 800));

		assertThat(plan(SettlementStrategy.MIN_TRANSFERS, balances).transferCount()).isEqualTo(8);
		assertThatThrownBy(() -> SettlementPlanner.plan(SettlementStrategy.MIN_TRANSFERS, balances,
				RelationshipGraph.EMPTY, new SettlementConstraints(Set.of(), 1000L)))
				.isInstanceOf(BadRequestException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo("UNSUPPORTED_OPTIMIZATION_SIZE");
	}

	@Test
	void settledGroup_yieldsEmptyPlan() {
		for (SettlementStrategy strategy : SettlementStrategy.values()) {
			assertThat(plan(strategy, List.of(balance(A, 0), balance(B, 0))).transfers()).isEmpty();
		}
	}

	@Test
	void onlyExactStrategies_claimExactness() {
		List<ParticipantBalance> balances = List.of(balance(A, -5000), balance(B, 5000));
		assertThat(plan(SettlementStrategy.GREEDY, balances).exact()).isFalse();
		assertThat(plan(SettlementStrategy.MIN_TRANSFERS, balances).exact()).isTrue();
		assertThat(plan(SettlementStrategy.RELATIONSHIP_AWARE, balances).exact()).isTrue();
	}

	@Test
	void nonZeroSum_isRejected() {
		assertThatThrownBy(() -> plan(SettlementStrategy.GREEDY, List.of(balance(A, 1))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void equalInputs_produceIdenticalTransferLists() {
		// Determinism: equal-magnitude ties break by participant id, so the
		// same balances always yield the same ordered plan (stable UI, stable tests).
		List<ParticipantBalance> balances = List.of(
				balance(A, 9000), balance(B, -3000), balance(C, -3000), balance(D, -3000));

		List<PlanTransfer> first = plan(SettlementStrategy.GREEDY, balances).transfers();
		List<PlanTransfer> second = plan(SettlementStrategy.GREEDY, balances).transfers();
		assertThat(first).containsExactlyElementsOf(second);
		assertThat(first.stream().map(t -> t.payerParticipantId().toString()).toList()).isSorted();
	}

}
