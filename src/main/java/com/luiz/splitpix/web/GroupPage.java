package com.luiz.splitpix.web;

import com.luiz.splitpix.activity.ActivityItem;
import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.balance.SuggestedPayment;
import com.luiz.splitpix.group.Group;
import com.luiz.splitpix.participant.Participant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything one render of the group page needs. */
public record GroupPage(
		Group group,
		List<Participant> participants,
		List<ParticipantBalance> balances,
		List<SuggestedPayment> payments,
		List<ActivityItem> history,
		Map<UUID, String> namesById,
		long outstandingCents,
		String expenseIdempotencyKey,
		String participantIdempotencyKey) {

	public boolean settled() {
		return payments.isEmpty();
	}

	public boolean hasHistory() {
		return !history.isEmpty();
	}

}
