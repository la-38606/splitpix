package com.luiz.splitpix.group;

import java.util.UUID;

public record CreateGroupResult(
		UUID groupId,
		String inviteToken,
		UUID creatorParticipantId) {
}
