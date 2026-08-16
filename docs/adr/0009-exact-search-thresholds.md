# ADR 0009: Exact search refuses oversized groups instead of degrading

Status: accepted

## Context

Minimum-transfer settlement is NP-hard (it embeds subset-sum), so the exact
strategies cannot run at every group size. Something has to happen when a
group is too large: silently fall back to greedy, silently truncate, or
refuse. The threshold itself also has to come from somewhere.

## Decision

Both thresholds are measured, not chosen by feel: 200 randomized adversarial
instances per configuration (values drawn to make zero-sum subsets rare) on a
laptop put the worst case at 59 ms for ten nonzero balances without a cap and
136 ms for eight with a per-transfer cap. The limits are ten and eight; past
them the API answers 400 `UNSUPPORTED_OPTIMIZATION_SIZE`. A capped search
gets the lower bound because a saturating move zeroes nobody, so the memo key
must include used edges and states repeat far less.

An explicit request for an exact strategy is never silently downgraded: the
caller asked for a guarantee, and a heuristic result wearing an exact label —
or even arriving unlabeled — would be a lie of omission. The refusal is the
honest answer. The one place a fallback exists is the page default
("recommended"), which is defined as relationship-aware within the threshold
and greedy beyond it, and the response always names the strategy actually
used.

A deterministic node budget (5,000,000 states) backstops the thresholds.
Identical input explores identical states, so for a given request the budget
either always trips or never does — no flaky behavior — and a trip surfaces
as the same 400. `PlanSearchPropertyTest.worstCasesAtTheThreshold` holds
adversarial boundary instances inside the budget.

## Alternatives considered

- **Silent fallback to greedy on the exact endpoints.** Rejected as above.
- **Bigger threshold with a timeout.** Timeouts make responses depend on host
  load: the same request succeeds on a fast machine and 500s on a slow one.
  The node budget is machine-independent.
- **Anytime search returning best-so-far.** Reasonable engineering, but the
  result would need a third label between exact and heuristic, and the payoff
  (groups of 11+ people with 11+ distinct nonzero balances wanting proofs) is
  marginal. Greedy already handles those groups.

## Consequences

The comparison endpoint lists refused strategies under `skipped` with the
error code, so the UI can say "unavailable at this group size" instead of
erroring. If the search core ever gets faster, re-running the measurement and
raising two constants is the whole migration.
