package com.luiz.splitpix.expense;

import java.util.UUID;

public record ExpenseShare(UUID participantId, long amountCents) {
}
