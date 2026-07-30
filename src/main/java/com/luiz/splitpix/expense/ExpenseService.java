package com.luiz.splitpix.expense;

import com.luiz.splitpix.common.BadRequestException;
import com.luiz.splitpix.group.GroupRepository;
import com.luiz.splitpix.group.GroupService;
import com.luiz.splitpix.participant.Participant;
import com.luiz.splitpix.participant.ParticipantRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {

	private final GroupService groupService;
	private final GroupRepository groupRepository;
	private final ParticipantRepository participantRepository;
	private final ExpenseRepository expenseRepository;

	public ExpenseService(GroupService groupService, GroupRepository groupRepository,
			ParticipantRepository participantRepository, ExpenseRepository expenseRepository) {
		this.groupService = groupService;
		this.groupRepository = groupRepository;
		this.participantRepository = participantRepository;
		this.expenseRepository = expenseRepository;
	}

	/**
	 * Expense creation transaction (design doc 13.1) under the group lock
	 * (13.3): token check, lock, idempotency check, validation, inserts —
	 * all in one transaction. The lock serializes every accounting write in
	 * the group, so the idempotency check is race-free once it holds.
	 */
	@Transactional
	public CreateExpenseResult create(UUID groupId, String inviteToken, String idempotencyKey,
			CreateExpenseRequest request) {
		groupService.requireGroup(groupId, inviteToken);
		validateIdempotencyKey(idempotencyKey);

		groupRepository.lockById(groupId);

		var existing = expenseRepository.findByGroupIdAndIdempotencyKey(groupId, idempotencyKey);
		if (existing.isPresent()) {
			Expense expense = existing.get();
			return new CreateExpenseResult(expense, expenseRepository.findShares(expense.id()), false);
		}

		List<ExpenseShare> shares = validate(groupId, request);

		UUID expenseId = UUID.randomUUID();
		var createdAt = expenseRepository.insertExpense(expenseId, groupId, request.paidByParticipantId(),
				request.description(), request.totalCents(), idempotencyKey);
		expenseRepository.insertShares(expenseId, shares);

		Expense expense = new Expense(expenseId, groupId, request.paidByParticipantId(),
				request.description(), request.totalCents(), idempotencyKey, createdAt);
		return new CreateExpenseResult(expense, shares, true);
	}

	private static void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED");
		}
		if (idempotencyKey.length() > 120) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
	}

	private List<ExpenseShare> validate(UUID groupId, CreateExpenseRequest request) {
		if (request.totalCents() <= 0) {
			throw new BadRequestException("INVALID_EXPENSE_TOTAL");
		}

		Set<UUID> members = participantRepository.findByGroupId(groupId).stream()
				.map(Participant::id)
				.collect(Collectors.toSet());
		if (!members.contains(request.paidByParticipantId())) {
			throw new BadRequestException("PARTICIPANT_NOT_IN_GROUP");
		}

		Set<UUID> seen = new HashSet<>();
		long sum = 0;
		for (var share : request.shares()) {
			if (!members.contains(share.participantId())) {
				throw new BadRequestException("PARTICIPANT_NOT_IN_GROUP");
			}
			if (!seen.add(share.participantId())) {
				throw new BadRequestException("DUPLICATE_SHARE_PARTICIPANT");
			}
			if (share.amountCents() < 0) {
				throw new BadRequestException("INVALID_SHARE_AMOUNT");
			}
			try {
				sum = Math.addExact(sum, share.amountCents());
			}
			catch (ArithmeticException e) {
				throw new BadRequestException("INVALID_EXPENSE_ALLOCATION");
			}
		}
		if (sum != request.totalCents()) {
			throw new BadRequestException("INVALID_EXPENSE_ALLOCATION");
		}

		return request.shares().stream()
				.map(s -> new ExpenseShare(s.participantId(), s.amountCents()))
				.toList();
	}

}
