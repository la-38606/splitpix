package com.luiz.splitpix.participant;

import java.time.Instant;
import java.util.UUID;

public record Participant(
		UUID id,
		UUID groupId,
		String displayName,
		PixKeyType pixKeyType,
		String pixKeyValue,
		Instant createdAt) {
}
