# ADR 0010: What counts as a financial relationship

Status: accepted

## Context

The relationship-aware strategy needs a precise answer to "have these two
people transacted?" A vague heuristic here would make the strategy's central
claim — zero or few new payment relationships — unfalsifiable. The rule has
to be derivable from the ledger alone, because the ledger is the only state
the system keeps.

## Decision

Participants A and B are related when at least one of these rows exists:

1. an expense paid by A in which B holds a share greater than zero, or the
   mirror image;
2. a completed settlement between A and B, in either direction.

The graph is undirected and unweighted. A zero share creates no edge: being
listed on a split you owed nothing for is bookkeeping, not a financial
relationship. Paying an expense relates the payer to each sharer, but does
not relate the sharers to each other — two people who both ate Ana's dinner
have each transacted with Ana, not with one another. Settling a suggested
payment creates a real edge like any other settlement, so a "novel" pair
stops being novel once someone actually pays through it.

`RelationshipRepository` derives the edge set in one query (expense-payer ×
positive shares, union completed settlements); `RelationshipGraph` is a value
object over normalized unordered pairs.

## Alternatives considered

- **Weighted edges (relationship strength).** Every weighting scheme tried on
  paper (count of shared expenses, total value exchanged, recency decay)
  needed arbitrary constants that changed which plan "wins" without any
  principled way to defend the constants. Unweighted membership keeps the
  objective statement exact: an edge either exists or it does not.
- **Sharers related to each other.** Tempting ("they were at the same
  dinner"), but the money only flowed through the payer. Basing edges on
  actual money flow keeps the rule explainable in one sentence.
- **Directional edges.** Money direction rarely matters socially — if Ana
  paid for Bruno before, Bruno paying Ana back is unremarkable. Undirected
  matches the intuition the feature exists to serve.

## Consequences

The rule is pinned by tests: `SettlementPlanApiTest` builds groups where the
expense structure forces known graphs and asserts novel-edge counts through
the public API. If the rule ever changes, those tests fail before any plan
quietly changes shape. The known limitation: relationships outside the group's
own ledger (the same two people in another SplitPix group, or in real life)
are invisible, which is a deliberate consequence of groups being isolated.
