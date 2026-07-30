package com.luiz.splitpix.balance;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/balances")
public class BalanceController {

	private final BalanceService balanceService;

	public BalanceController(BalanceService balanceService) {
		this.balanceService = balanceService;
	}

	@GetMapping
	public BalancesResponse get(@PathVariable UUID groupId, @RequestParam String token) {
		return new BalancesResponse(groupId, balanceService.getBalances(groupId, token));
	}

	public record BalancesResponse(UUID groupId, List<ParticipantBalance> balances) {
	}

}
