package com.luiz.splitpix.balance;

import java.util.UUID;

public record ParticipantBalance(UUID participantId, String displayName, long balanceCents) {
}
