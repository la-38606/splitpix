package com.luiz.splitpix.balance;

import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.group.GroupService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceService {

	private final GroupService groupService;
	private final BalanceRepository balanceRepository;
	private final BalanceExplanationRepository explanationRepository;

	public BalanceService(GroupService groupService, BalanceRepository balanceRepository,
			BalanceExplanationRepository explanationRepository) {
		this.groupService = groupService;
		this.balanceRepository = balanceRepository;
		this.explanationRepository = explanationRepository;
	}

	@Transactional(readOnly = true)
	public List<ParticipantBalance> getBalances(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		return balanceRepository.computeBalances(groupId);
	}

	/**
	 * The provenance of one participant's balance. REPEATABLE_READ so the
	 * entry list and the aggregate balance read the same snapshot, which is
	 * what lets the closing cross-check hold under concurrent writes.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public BalanceExplanation explain(UUID groupId, String inviteToken, UUID participantId) {
		groupService.requireGroup(groupId, inviteToken);
		List<ParticipantBalance> balances = balanceRepository.computeBalances(groupId);
		ParticipantBalance balance = balances.stream()
				.filter(b -> b.participantId().equals(participantId))
				.findFirst()
				.orElseThrow(() -> new BadRequestException("PARTICIPANT_NOT_IN_GROUP"));

		Map<UUID, String> names = balances.stream()
				.collect(Collectors.toMap(ParticipantBalance::participantId, ParticipantBalance::displayName));
		List<BalanceExplanation.Entry> entries = explanationRepository.findEntries(groupId, participantId)
				.stream()
				.map(entry -> new BalanceExplanation.Entry(entry.type(), entry.sourceId(),
						entry.description(), entry.counterpartyParticipantId(),
						names.get(entry.counterpartyParticipantId()), entry.amountCents(),
						entry.createdAt()))
				.toList();

		// The explanation must account for the balance to the centavo. Both
		// reads share one snapshot, so a mismatch can only mean the two
		// queries disagree about the ledger — a bug worth a 500, not a
		// plausible-looking statement.
		long explained = entries.stream().mapToLong(BalanceExplanation.Entry::amountCents).sum();
		if (explained != balance.balanceCents()) {
			throw new IllegalStateException("balance explanation sums to " + explained
					+ " but the balance is " + balance.balanceCents());
		}
		return new BalanceExplanation(groupId, participantId, balance.displayName(),
				balance.balanceCents(), entries);
	}

}
