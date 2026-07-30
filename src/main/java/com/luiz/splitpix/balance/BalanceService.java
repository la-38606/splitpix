package com.luiz.splitpix.balance;

import com.luiz.splitpix.group.GroupService;
import com.luiz.splitpix.participant.Participant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.luiz.splitpix.participant.ParticipantRepository;
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
		Map<UUID, Participant> participants = participantRepository.findByGroupId(groupId).stream()
				.collect(java.util.stream.Collectors.toMap(Participant::id, Function.identity()));
		return DebtSimplifier.simplify(balanceRepository.computeBalances(groupId)).stream()
				.map(transfer -> new SuggestedPayment(
						transfer.payerParticipantId(),
						participants.get(transfer.payerParticipantId()).displayName(),
						transfer.recipientParticipantId(),
						participants.get(transfer.recipientParticipantId()).displayName(),
						participants.get(transfer.recipientParticipantId()).pixKeyValue(),
						transfer.amountCents()))
				.toList();
	}

}
