package com.luiz.splitpix.settlement.plan;

import com.luiz.splitpix.activity.ActivityRepository;
import com.luiz.splitpix.balance.BalanceRepository;
import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.common.Money;
import com.luiz.splitpix.group.GroupService;
import com.luiz.splitpix.participant.Participant;
import com.luiz.splitpix.participant.ParticipantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives settlement plans from current ledger state. Nothing here is ever
 * stored: a plan is a pure function of (balances, relationships, strategy,
 * constraints), and the returned ledger revision tells the caller which
 * ledger state it was derived from (ADR 0012).
 *
 * Each public method is one REPEATABLE_READ transaction, so balances,
 * relationships and revision always describe the same snapshot even while
 * writers are committing.
 */
@Service
public class SettlementPlanService {

	private final GroupService groupService;
	private final BalanceRepository balanceRepository;
	private final ParticipantRepository participantRepository;
	private final RelationshipRepository relationshipRepository;
	private final ActivityRepository activityRepository;

	public SettlementPlanService(GroupService groupService, BalanceRepository balanceRepository,
			ParticipantRepository participantRepository, RelationshipRepository relationshipRepository,
			ActivityRepository activityRepository) {
		this.groupService = groupService;
		this.balanceRepository = balanceRepository;
		this.participantRepository = participantRepository;
		this.relationshipRepository = relationshipRepository;
		this.activityRepository = activityRepository;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public GroupPlan plan(UUID groupId, String inviteToken, SettlementStrategy strategy,
			SettlementConstraints constraints) {
		groupService.requireGroup(groupId, inviteToken);
		List<ParticipantBalance> balances = balanceRepository.computeBalances(groupId);
		validate(strategy, constraints, balances);
		SettlementPlan plan = SettlementPlanner.plan(strategy, balances,
				relationshipRepository.findByGroupId(groupId), constraints);
		return new GroupPlan(groupId, activityRepository.ledgerRevision(groupId),
				toView(groupId, plan, balances));
	}

	/**
	 * The default the group page shows: RELATIONSHIP_AWARE when the group is
	 * small enough for the exact search, GREEDY otherwise (ADR 0013).
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public GroupPlan recommended(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		List<ParticipantBalance> balances = balanceRepository.computeBalances(groupId);
		SettlementStrategy strategy = SettlementPlanner.supportsExact(balances)
				? SettlementStrategy.RELATIONSHIP_AWARE
				: SettlementStrategy.GREEDY;
		SettlementPlan plan = SettlementPlanner.plan(strategy, balances,
				relationshipRepository.findByGroupId(groupId), SettlementConstraints.NONE);
		return new GroupPlan(groupId, activityRepository.ledgerRevision(groupId),
				toView(groupId, plan, balances));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PlanComparison compare(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		List<ParticipantBalance> balances = balanceRepository.computeBalances(groupId);
		RelationshipGraph relationships = relationshipRepository.findByGroupId(groupId);

		List<PlanView> plans = new ArrayList<>();
		List<PlanComparison.Skipped> skipped = new ArrayList<>();
		for (SettlementStrategy strategy : SettlementStrategy.values()) {
			if (strategy != SettlementStrategy.GREEDY && !SettlementPlanner.supportsExact(balances)) {
				skipped.add(new PlanComparison.Skipped(strategy, "UNSUPPORTED_OPTIMIZATION_SIZE"));
				continue;
			}
			plans.add(toView(groupId, SettlementPlanner.plan(strategy, balances,
					relationships, SettlementConstraints.NONE), balances));
		}
		return new PlanComparison(groupId, activityRepository.ledgerRevision(groupId), plans, skipped);
	}

	private void validate(SettlementStrategy strategy, SettlementConstraints constraints,
			List<ParticipantBalance> balances) {
		if (constraints.none()) {
			return;
		}
		if (strategy == SettlementStrategy.GREEDY) {
			throw new BadRequestException("INVALID_SETTLEMENT_CONSTRAINT");
		}
		Long cap = constraints.maxTransferCents();
		if (cap != null && (cap <= 0 || cap > Money.MAX_AMOUNT_CENTS)) {
			throw new BadRequestException("INVALID_SETTLEMENT_CONSTRAINT");
		}
		var members = balances.stream().map(ParticipantBalance::participantId).collect(Collectors.toSet());
		for (SettlementConstraints.Pair pair : constraints.forbiddenPairs()) {
			if (pair.payerParticipantId().equals(pair.recipientParticipantId())) {
				throw new BadRequestException("INVALID_SETTLEMENT_CONSTRAINT");
			}
			if (!members.contains(pair.payerParticipantId())
					|| !members.contains(pair.recipientParticipantId())) {
				throw new BadRequestException("PARTICIPANT_NOT_IN_GROUP");
			}
		}
	}

	private PlanView toView(UUID groupId, SettlementPlan plan, List<ParticipantBalance> balances) {
		// Names come from the balances themselves — one snapshot, no lookup
		// that can miss a participant. The Pix-key map is a separate read
		// inside the same REPEATABLE_READ transaction.
		Map<UUID, ParticipantBalance> byId = balances.stream()
				.collect(Collectors.toMap(ParticipantBalance::participantId, Function.identity()));
		Map<UUID, String> pixKeys = participantRepository.findByGroupId(groupId).stream()
				.filter(p -> p.pixKeyValue() != null)
				.collect(Collectors.toMap(Participant::id, Participant::pixKeyValue));

		List<PlanView.TransferView> transfers = plan.transfers().stream()
				.map(t -> new PlanView.TransferView(
						t.payerParticipantId(),
						byId.get(t.payerParticipantId()).displayName(),
						t.recipientParticipantId(),
						byId.get(t.recipientParticipantId()).displayName(),
						pixKeys.get(t.recipientParticipantId()),
						t.amountCents(),
						t.novelRelationship()))
				.toList();
		return new PlanView(plan.strategy(), plan.exact(), plan.transferCount(),
				plan.novelRelationshipEdges(), plan.totalAmountCents(), transfers);
	}

}
