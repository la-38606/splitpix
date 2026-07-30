package com.luiz.splitpix.settlement;

/** {@code created} is false when an idempotent replay returned the existing settlement. */
public record CompleteSettlementResult(Settlement settlement, boolean created) {
}
