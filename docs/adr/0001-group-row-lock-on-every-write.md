# ADR 0001: One row lock per group, taken by every write

Status: accepted

## Context

Balances are derived from expenses, shares and settlements (ADR 0002), so a
settlement is only valid relative to the balances at the moment it commits.
Under PostgreSQL's default `READ COMMITTED`, a transaction that validates
against a snapshot can be invalidated by a concurrent commit before it inserts.
The failure that matters: two settlements, each valid alone, jointly
over-settling a debtor.

## Decision

Every write path takes `SELECT id FROM groups WHERE id = ? FOR UPDATE`
(`GroupRepository.lockById`) before reading anything it validates against:
expense creation, settlement completion, and participant addition. Reads take
no lock.

## Alternatives considered

- **Lock the participant rows involved.** Unsound, not just slower: an expense
  insert changes balances without touching any participant row, so a concurrent
  expense can commit between a settlement's validation and its insert.
- **`SERIALIZABLE` isolation.** Correct, but converts the failure into
  serialization errors every caller must retry, and is harder to reason about
  than one explicit lock.
- **Optimistic locking (version column on the group).** Equivalent throughput
  for this workload, plus a retry path to build and test.

## Consequences

All writes in one group serialize; a five-person dinner never notices, a
thousand-member group would. In exchange, the no-over-settlement invariants
have one-paragraph proofs — deterministic lock pins in `GroupLockTest`, and
a genuine two-thread race demonstration in `SettlementConcurrencyTest`. No deadlock is constructible:
the only exclusive lock is the group row, always taken before any insert.
