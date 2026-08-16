package com.luiz.splitpix.balance;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BalanceExplanationController {

	private final BalanceService balanceService;

	public BalanceExplanationController(BalanceService balanceService) {
		this.balanceService = balanceService;
	}

	@GetMapping("/api/v1/groups/{groupId}/participants/{participantId}/balance-explanation")
	public BalanceExplanation explain(@PathVariable UUID groupId, @PathVariable UUID participantId,
			@RequestParam String token) {
		return balanceService.explain(groupId, token, participantId);
	}

}
