package com.luiz.splitpix.group;

import java.time.Instant;
import java.util.UUID;

public record Group(
		UUID id,
		String name,
		String inviteToken,
		Instant createdAt) {
}
