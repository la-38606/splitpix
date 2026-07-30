package com.luiz.splitpix.balance;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/suggested-payments")
public class SuggestedPaymentController {

	private final BalanceService balanceService;

	public SuggestedPaymentController(BalanceService balanceService) {
		this.balanceService = balanceService;
	}

	@GetMapping
	public SuggestedPaymentsResponse get(@PathVariable UUID groupId, @RequestParam String token) {
		return new SuggestedPaymentsResponse(balanceService.getSuggestedPayments(groupId, token));
	}

	public record SuggestedPaymentsResponse(List<SuggestedPayment> payments) {
	}

}
