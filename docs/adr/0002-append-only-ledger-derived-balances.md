# ADR 0002: Append-only ledger, balances derived not stored

Status: accepted

## Context

Every operation needs current balances, and a settlement needs them to be
exactly right. A stored balance column is a second source of truth that can
drift from the ledger; drift in an accounting system is the worst failure mode
available.

## Decision

No balance column exists. `BalanceRepository.computeBalances` derives every
balance on demand with one aggregate over four `UNION ALL` legs: expenses paid,
shares assigned, settlements sent, settlements received. Expenses and
settlements are never updated or deleted; a mistaken settlement is corrected
with a compensating expense (an opposite settlement is rejected by the
over-settlement checks — proven in
`SettlementApiTest.mistakenSettlement_isCorrectedByACompensatingExpense`).

## Alternatives considered

- **Balance column updated per write.** Fast reads, but every write path must
  maintain it correctly forever, and any bug corrupts state silently.
- **Materialized view.** Needs refreshing, which reintroduces staleness inside
  the settlement transaction — exactly where staleness is fatal.
- **Editable expenses.** An edit changes historical balances and can
  retroactively invalidate settlements that were valid when made.

## Consequences

Balance reads scan the group's rows (fine at this scale; the composite unique
constraints double as indexes). The zero-sum invariant is a property of the
query rather than something application code maintains. There is no way to
"fix" data by editing it, which is the point.
