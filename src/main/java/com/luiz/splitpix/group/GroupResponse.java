package com.luiz.splitpix.group;

import com.luiz.splitpix.participant.ParticipantResponse;
import java.util.List;
import java.util.UUID;

public record GroupResponse(
		UUID groupId,
		String name,
		List<ParticipantResponse> participants) {
}
