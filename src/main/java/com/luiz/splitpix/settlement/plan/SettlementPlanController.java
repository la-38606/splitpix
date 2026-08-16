package com.luiz.splitpix.settlement.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plans are derived, never stored, so there is no idempotency key here and
 * the POST is safe to repeat. POST exists only because constraints need a
 * request body; it writes nothing.
 */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/settlement-plan")
public class SettlementPlanController {

	private final SettlementPlanService settlementPlanService;

	public SettlementPlanController(SettlementPlanService settlementPlanService) {
		this.settlementPlanService = settlementPlanService;
	}

	@GetMapping
	public GroupPlan get(@PathVariable UUID groupId, @RequestParam String token,
			@RequestParam(defaultValue = "GREEDY") SettlementStrategy strategy) {
		return settlementPlanService.plan(groupId, token, strategy, SettlementConstraints.NONE);
	}

	@PostMapping
	public GroupPlan withConstraints(@PathVariable UUID groupId, @RequestParam String token,
			@Valid @RequestBody PlanRequest request) {
		return settlementPlanService.plan(groupId, token, request.strategy(), request.toConstraints());
	}

	@GetMapping("/compare")
	public PlanComparison compare(@PathVariable UUID groupId, @RequestParam String token) {
		return settlementPlanService.compare(groupId, token);
	}

	public record PlanRequest(
			@NotNull SettlementStrategy strategy,
			@Valid ConstraintsRequest constraints) {

		public record ConstraintsRequest(
				@Size(max = 100) List<@NotNull @Valid PairRequest> forbiddenPairs,
				Long maxTransferCents) {
		}

		public record PairRequest(
				@NotNull UUID payerParticipantId,
				@NotNull UUID recipientParticipantId) {
		}

		public SettlementConstraints toConstraints() {
			if (constraints == null) {
				return SettlementConstraints.NONE;
			}
			Set<SettlementConstraints.Pair> pairs = new HashSet<>();
			if (constraints.forbiddenPairs() != null) {
				for (PairRequest pair : constraints.forbiddenPairs()) {
					pairs.add(new SettlementConstraints.Pair(
							pair.payerParticipantId(), pair.recipientParticipantId()));
				}
			}
			return new SettlementConstraints(pairs, constraints.maxTransferCents());
		}

	}

}
