# ADR 0012: Balance explanations reuse the balance query's own legs

Status: accepted

## Context

"Why do I owe R$ 172,43?" deserves a real answer, and the obvious
implementation is dangerous: a second, prettier calculation that walks the
same tables slightly differently and drifts from the number it claims to
explain. An explanation that disagrees with the balance is worse than none.

## Decision

The explanation endpoint returns the same four legs the balance aggregate
sums — expenses paid (+total), shares held (−share), settlements sent
(+amount), settlements received (−amount) — as rows instead of an aggregate,
in ledger order. Zero shares are omitted; they contribute nothing.

Two mechanisms keep the explanation and the balance the same truth:

1. Both queries run in one `REPEATABLE_READ` transaction, so they read the
   same snapshot even mid-write.
2. The service sums the entries and compares against the balance query's
   result. A mismatch throws: the user gets a 500, not a statement that
   almost adds up. This is the same fail-loud posture as `PlanInvariants`.

The API shape is one entry per ledger leg, with type, source id, description
or counterparty, signed amount and timestamp. The statement page renders the
rows and a closing total; the closing total is the balance by construction.

## Alternatives considered

- **Netting per expense** ("Ana paid R$ 26 on behalf of others"). Friendlier
  at first glance, but it invents a derived quantity that no ledger row
  carries, and the moment display logic disagrees with the aggregate there is
  no cheap way to notice. Raw legs are self-auditing: their sum is the check.
- **A materialized explanation table.** Denormalizes what a query already
  answers, and inherits every staleness problem ADR 0002 exists to avoid.
- **Trusting the two queries to agree without the runtime check.** They
  should. The check is one subtraction per request and converts "should" into
  "must".

## Consequences

The invariant `sum(entries) == balanceCents` is asserted in production on
every request and in tests against randomized activity
(`BalanceExplanationApiTest`). Adding a fifth accounting leg someday means
updating both queries together or every explanation request fails loudly —
which is the point.
