package com.luiz.splitpix.balance;

import com.luiz.splitpix.group.GroupService;
import com.luiz.splitpix.participant.Participant;
import com.luiz.splitpix.participant.ParticipantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceService {

	private final GroupService groupService;
	private final BalanceRepository balanceRepository;
	private final ParticipantRepository participantRepository;

	public BalanceService(GroupService groupService, BalanceRepository balanceRepository,
			ParticipantRepository participantRepository) {
		this.groupService = groupService;
		this.balanceRepository = balanceRepository;
		this.participantRepository = participantRepository;
	}

	@Transactional(readOnly = true)
	public List<ParticipantBalance> getBalances(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		return balanceRepository.computeBalances(groupId);
	}

	/** Generated on demand from current balances, never stored (design doc 12.6). */
	@Transactional(readOnly = true)
	public List<SuggestedPayment> getSuggestedPayments(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		List<ParticipantBalance> balances = balanceRepository.computeBalances(groupId);
		// Names come from the balances themselves — one snapshot, no lookup that
		// can miss a participant added between two statements. The Pix-key map
		// is a separate read; a miss degrades to a null key, never an error.
		Map<UUID, ParticipantBalance> byId = balances.stream()
				.collect(Collectors.toMap(ParticipantBalance::participantId, Function.identity()));
		Map<UUID, String> pixKeys = participantRepository.findByGroupId(groupId).stream()
				.filter(p -> p.pixKeyValue() != null)
				.collect(Collectors.toMap(Participant::id, Participant::pixKeyValue));
		return DebtSimplifier.simplify(balances).stream()
				.map(transfer -> new SuggestedPayment(
						transfer.payerParticipantId(),
						byId.get(transfer.payerParticipantId()).displayName(),
						transfer.recipientParticipantId(),
						byId.get(transfer.recipientParticipantId()).displayName(),
						pixKeys.get(transfer.recipientParticipantId()),
						transfer.amountCents()))
				.toList();
	}

}
