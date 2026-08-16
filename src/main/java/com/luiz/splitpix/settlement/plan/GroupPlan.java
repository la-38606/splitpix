package com.luiz.splitpix.settlement.plan;

import java.util.UUID;

/**
 * A plan tied to the ledger state that produced it. {@code ledgerRevision}
 * counts the group's accounting entries (expenses plus completed
 * settlements); the ledger is append-only, so a changed revision means a
 * previously generated plan may be stale.
 */
public record GroupPlan(UUID groupId, long ledgerRevision, PlanView plan) {
}
