package com.luiz.splitpix.settlement;

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
@RequestMapping("/api/v1/groups/{groupId}/settlements")
public class SettlementController {

	private final SettlementService settlementService;

	public SettlementController(SettlementService settlementService) {
		this.settlementService = settlementService;
	}

	/** 201 on completion; 200 when the idempotency key replays an existing settlement. */
	@PostMapping
	public ResponseEntity<SettlementResponse> complete(@PathVariable UUID groupId,
			@RequestParam String token,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CompleteSettlementRequest request) {
		CompleteSettlementResult result = settlementService.complete(groupId, token, idempotencyKey, request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(SettlementResponse.from(result.settlement()));
	}

}
