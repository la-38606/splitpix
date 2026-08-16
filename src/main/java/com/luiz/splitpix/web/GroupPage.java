package com.luiz.splitpix.web;

import com.luiz.splitpix.activity.ActivityItem;
import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.group.Group;
import com.luiz.splitpix.participant.Participant;
import com.luiz.splitpix.settlement.plan.GroupPlan;
import com.luiz.splitpix.settlement.plan.PlanView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything one render of the group page needs. */
public record GroupPage(
		Group group,
		List<Participant> participants,
		List<ParticipantBalance> balances,
		GroupPlan plan,
		List<ActivityItem> history,
		Map<UUID, String> namesById,
		long outstandingCents,
		String expenseIdempotencyKey,
		String paymentIdempotencyKey) {

	public List<PlanView.TransferView> payments() {
		return plan.plan().transfers();
	}

	public boolean settled() {
		return payments().isEmpty();
	}

	public boolean hasHistory() {
		return !history.isEmpty();
	}

}
