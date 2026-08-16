# ADR 0008: Three settlement strategies instead of one algorithm

Status: accepted (supersedes the strategy part of ADR 0005)

## Context

ADR 0005 shipped a single greedy simplifier and dismissed exact minimization
as not worth exponential cost. Two things changed that judgment. First, the
greedy result is measurably not optimal: with balances +500, +400, −400,
−300, −200 it produces four transfers where three settle the group, because
pairing largest-with-largest destroys the {+400, −400} component. Second,
transfer count is not the only thing a group cares about. A minimal plan can
route money between two people who never shared an expense, and "pay someone
you barely know" is a real cost that a pure count ignores. Multiple settlement
graphs produce identical final balances; choosing among them is a decision,
and the old design hid that decision inside one heuristic.

## Decision

`SettlementPlanner` dispatches three strategies over one plan model:

- **GREEDY** — the ADR 0005 algorithm, unchanged. At most n−1 transfers,
  O(n log n), any group size. Claims nothing about optimality
  (`exact = false` always).
- **MIN_TRANSFERS** — provably fewest transfers, by exhaustive search over
  basic plans (every move zeroes a participant or saturates a capped edge;
  any plan reduces to a basic one on a subset of its edges, so the optimum is
  in the searched space).
- **RELATIONSHIP_AWARE** — lexicographic objective: first minimize transfers
  between unrelated participants, then minimize transfer count. Same search
  core, with novel edges charged before transfers.

The two exact strategies share `ExactPlanSearch`, memoized on the remaining
balance vector. Every plan reports `strategy`, `exact`, `transferCount`,
`novelRelationshipEdges` and per-transfer novelty, so callers can see what
was optimized and what was merely found. Every plan also passes
`PlanInvariants.verify` before leaving the service.

## Alternatives considered

- **One configurable objective function.** A single weighted score (α·transfers
  + β·novel edges) forces users to invent weights and produces answers nobody
  can explain. Lexicographic objectives have clean statements: "fewest new
  relationships, then fewest payments."
- **Integer programming / external solver.** Correct, but drags in a solver
  dependency for problems capped at ten nonzero balances. The DFS is ~150
  lines and its guarantees are testable against brute force in-process.
- **Making RELATIONSHIP_AWARE the only strategy.** It subsumes MIN_TRANSFERS
  only when relationships are dense; on sparse graphs it may spend transfers
  the group does not want to spend. Showing the tradeoff beats picking a side.

## Consequences

The API takes a `strategy` parameter and a compare endpoint returns all three
plans for one snapshot. `PlanSearchPropertyTest` cross-checks MIN_TRANSFERS
against an independent partition-bound DP and both exact strategies against
exhaustive enumeration; `SettlementPlannerTest.greedyIsNotAlwaysMinimal` pins
the counterexample above. The cost is three code paths to keep honest, which
is exactly what the shared plan validator and the property tests are for.
