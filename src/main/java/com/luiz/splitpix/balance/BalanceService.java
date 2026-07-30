package com.luiz.splitpix.balance;

import com.luiz.splitpix.group.GroupService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceService {

	private final GroupService groupService;
	private final BalanceRepository balanceRepository;

	public BalanceService(GroupService groupService, BalanceRepository balanceRepository) {
		this.groupService = groupService;
		this.balanceRepository = balanceRepository;
	}

	@Transactional(readOnly = true)
	public List<ParticipantBalance> getBalances(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		return balanceRepository.computeBalances(groupId);
	}

}
