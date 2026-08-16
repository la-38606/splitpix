package com.luiz.splitpix.settlement;

import com.luiz.splitpix.balance.BalanceRepository;
import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.common.ConflictException;
import com.luiz.splitpix.common.IdempotencyKeys;
import com.luiz.splitpix.common.Money;
import com.luiz.splitpix.common.RequestHashes;
import com.luiz.splitpix.group.GroupRepository;
import com.luiz.splitpix.group.GroupService;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

	private final GroupService groupService;
	private final GroupRepository groupRepository;
	private final BalanceRepository balanceRepository;
	private final SettlementRepository settlementRepository;

	public SettlementService(GroupService groupService, GroupRepository groupRepository,
			BalanceRepository balanceRepository, SettlementRepository settlementRepository) {
		this.groupService = groupService;
		this.groupRepository = groupRepository;
		this.balanceRepository = balanceRepository;
		this.settlementRepository = settlementRepository;
	}

	/**
	 * Settlement transaction under the group lock (docs/design.md sections 3.2 and 4.2):
	 * token check, lock, idempotency check, then balance recomputation INSIDE
	 * the transaction — invariants 4 and 5 hold because no other accounting
	 * write can commit between validation and insert.
	 */
	@Transactional
	public CompleteSettlementResult complete(UUID groupId, String inviteToken, String idempotencyKey,
			CompleteSettlementRequest request) {
		groupService.requireGroup(groupId, inviteToken);
		IdempotencyKeys.validate(idempotencyKey);
		String requestHash = RequestHashes.of(request.payerParticipantId(),
				request.recipientParticipantId(), request.amountCents());

		groupRepository.lockById(groupId);

		var existing = settlementRepository.findByGroupIdAndIdempotencyKey(groupId, idempotencyKey);
		if (existing.isPresent()) {
			if (!existing.get().requestHash().equals(requestHash)) {
				throw new ConflictException("IDEMPOTENCY_CONFLICT");
			}
			return new CompleteSettlementResult(existing.get(), false);
		}

		long amount = request.amountCents();
		if (amount <= 0 || amount > Money.MAX_AMOUNT_CENTS) {
			throw new BadRequestException("INVALID_SETTLEMENT_AMOUNT");
		}
		if (request.payerParticipantId().equals(request.recipientParticipantId())) {
			throw new BadRequestException("INVALID_SETTLEMENT_PARTICIPANTS");
		}

		Map<UUID, Long> balances = balanceRepository.computeBalances(groupId).stream()
				.collect(Collectors.toMap(ParticipantBalance::participantId, ParticipantBalance::balanceCents));
		Long payerBalance = balances.get(request.payerParticipantId());
		Long recipientBalance = balances.get(request.recipientParticipantId());
		if (payerBalance == null || recipientBalance == null) {
			throw new BadRequestException("PARTICIPANT_NOT_IN_GROUP");
		}

		// Invariant 4: the payer must owe at least this amount (balance <= -amount).
		// Invariant 5: the recipient must be owed at least this amount.
		if (payerBalance > -amount || recipientBalance < amount) {
			throw new ConflictException("SETTLEMENT_EXCEEDS_DEBT");
		}

		UUID settlementId = UUID.randomUUID();
		var createdAt = settlementRepository.insert(settlementId, groupId, request.payerParticipantId(),
				request.recipientParticipantId(), amount, idempotencyKey, requestHash);

		return new CompleteSettlementResult(new Settlement(settlementId, groupId,
				request.payerParticipantId(), request.recipientParticipantId(), amount,
				idempotencyKey, requestHash, SettlementRepository.STATUS_COMPLETED, createdAt), true);
	}

}
