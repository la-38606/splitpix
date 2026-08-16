# ADR 0011: Two settlement constraints, enforced exactly or not at all

Status: accepted

## Context

Real groups have rules the optimizer cannot infer: two people who should not
transact, or a bank's per-transfer ceiling. The temptation is to accept a
long list of constraint types; the risk is a solver that half-honors some of
them. A constraint that is "usually" respected is worse than no constraint,
because the caller stops checking.

## Decision

Exactly two constraint types, both enforced by the exact search core:

- **Forbidden pairs**, directed: forbidding A→B still allows B→A. Directed is
  the more expressive primitive (the symmetric case is two entries), and the
  payer/recipient asymmetry is real — "Diego shouldn't have to pay Ana" says
  nothing about Ana paying Diego.
- **Per-transfer cap** (`maxTransferCents`). A plan is a list of payment
  instructions with at most one instruction per (payer, recipient) pair, so
  the cap can make a two-person debt genuinely infeasible. That is the
  correct reading: "pay the same person four times" is not a plan anyone
  wants suggested.

Constraints fold into the search as pruned moves, so satisfaction is by
construction, then re-checked by `PlanInvariants` on the way out. GREEDY
rejects any constraint with 400 `INVALID_SETTLEMENT_CONSTRAINT`: honoring
constraints greedily can fail on instances that are feasible, and a strategy
that sometimes reports false infeasibility is broken. When no plan satisfies
the constraints, the API answers 409 `NO_FEASIBLE_SETTLEMENT_PLAN` — a
conflict with current ledger state, same family as `SETTLEMENT_EXCEEDS_DEBT`.

## Alternatives considered

- **More constraint types** (max outgoing transfers per person, preferred
  pairs, per-person caps). Each adds a dimension to the search state or the
  objective. Two constraints implemented exactly beat six implemented
  approximately; the search core can grow types later if a real need shows up.
- **Best-effort constraints on greedy.** Rejected as above — the failure mode
  is silent and wrong.
- **Splitting capped debts across repeated instructions.** Makes every cap
  satisfiable, but turns "pay Ana R$ 300" into "pay Ana R$ 90 four times",
  which no one would follow. Infeasibility is the truthful answer.

## Consequences

Feasibility filtering plus caps put used-edge state into the search, which is
why capped searches get the lower size threshold (ADR 0009). The randomized
cross-check in `PlanSearchPropertyTest` generates constraint sets and
verifies both the optimum and the infeasibility verdicts against exhaustive
enumeration, so "no feasible plan" is as tested as the plans themselves.
