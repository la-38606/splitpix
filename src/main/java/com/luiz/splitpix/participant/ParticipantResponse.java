package com.luiz.splitpix.participant;

import java.util.UUID;

public record ParticipantResponse(
		UUID participantId,
		String displayName,
		PixKeyType pixKeyType,
		String pixKeyValue) {

	public static ParticipantResponse from(Participant participant) {
		return new ParticipantResponse(
				participant.id(),
				participant.displayName(),
				participant.pixKeyType(),
				participant.pixKeyValue());
	}

}
