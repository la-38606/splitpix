package com.luiz.splitpix.expense;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateExpenseRequest(
		@NotBlank @Size(max = 200) String description,
		@NotNull UUID paidByParticipantId,
		@NotNull Long totalCents,
		// Constraints on the type argument, not the container: a container-level
		// @Valid skips null elements, which then NPE in the service. The size cap
		// bounds work per request; shares must be distinct group members anyway.
		@NotNull @Size(min = 1, max = 500) List<@NotNull @Valid ShareRequest> shares) {

	public CreateExpenseRequest {
		description = description == null ? null : description.strip();
	}

	public record ShareRequest(
			@NotNull UUID participantId,
			@NotNull Long amountCents) {
	}

}
