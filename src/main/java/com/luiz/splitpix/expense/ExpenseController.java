package com.luiz.splitpix.expense;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/expenses")
public class ExpenseController {

	private final ExpenseService expenseService;

	public ExpenseController(ExpenseService expenseService) {
		this.expenseService = expenseService;
	}

	/** 201 on creation; 200 when the idempotency key replays an existing expense. */
	@PostMapping
	public ResponseEntity<ExpenseResponse> create(@PathVariable UUID groupId,
			@RequestParam String token,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CreateExpenseRequest request) {
		CreateExpenseResult result = expenseService.create(groupId, token, idempotencyKey, request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(ExpenseResponse.from(result.expense(), result.shares()));
	}

}
