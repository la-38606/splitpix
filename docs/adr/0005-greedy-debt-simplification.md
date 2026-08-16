# ADR 0005: Greedy debt simplification, generated on demand

Status: partially superseded by ADR 0008 — greedy is now one of three
strategies rather than the only algorithm. The derived-not-stored decision
stands, extended with ledger revisions (plans are stamped with the revision
they were derived from).

## Context

After a few expenses, everyone owes everyone. The product's job is to turn net
balances into a short list of transfers. Minimizing the exact number of
transfers is a subset-sum-style problem (NP-hard in general).

## Decision

`GreedyOptimizer` (settlement/plan) repeatedly matches the largest debtor
with the largest creditor (two priority queues, deterministic tie-break by
participant id) and transfers the smaller magnitude. This guarantees at most
n-1 transfers in O(n log n). Suggestions are computed per request and never
stored.

## Alternatives considered

- **Exact minimum-transfer search.** Exponential in the worst case, and for a
  five-to-ten-person group the greedy result is already at or near optimal.
  Correctness of the accounting does not depend on transfer count.
- **Storing suggestions as obligations.** Any new expense invalidates them;
  stored obligations would need invalidation logic and could go stale between
  generation and payment. On-demand generation makes staleness impossible to
  store, and the settlement path re-validates against live balances anyway.

## Consequences

Suggested payments have no identity; completing one submits payer, recipient
and amount, and a stale suggestion is rejected with 409 rather than trusted.
A 300-seed randomized property test (`PlanSearchPropertyTest`) checks that
applying every suggestion zeroes all balances, amounts are positive, and
nobody pays themselves.
