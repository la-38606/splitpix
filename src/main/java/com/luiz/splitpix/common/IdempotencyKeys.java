package com.luiz.splitpix.common;

public final class IdempotencyKeys {

	private IdempotencyKeys() {
	}

	/** Shared by every idempotent write path; the column is VARCHAR(120). */
	public static void validate(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED");
		}
		if (idempotencyKey.length() > 120) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
	}

}
