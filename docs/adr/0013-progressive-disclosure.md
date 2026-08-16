# ADR 0013: Simple by default, technical on demand

Status: accepted

## Context

The optimizer produces genuinely interesting metadata: exactness proofs,
novel-edge counts, ledger revisions, balance vectors. Almost none of the
people splitting a dinner bill want any of it. The failure mode on each side
is real — hide everything and the project's substance is invisible; show
everything and the group page reads like a solver dashboard.

## Decision

The default group page shows what a non-technical person needs and nothing
else: balances, a list of suggested payments with Pix keys, history. The
depth is reachable, always one deliberate action away:

- **"Por que este plano?"** — a collapsed `<details>` under the payment list:
  strategy in plain words, transfer and novel-pair counts, whether the plan
  is provably optimal, and the link onward.
- **"por quê?"** next to each balance — the statement page (ADR 0012).
- **/planos** — the three strategies side by side with their metrics, an
  advanced form for constraints, and a "Detalhes técnicos" panel that shows
  internal enum names, exactness flags and the ledger revision.

Internal identifiers never appear on the default page (a test pins this);
user-facing strategy names are plain language from the message bundle
("Menos pagamentos", not `MIN_TRANSFERS`).

The default plan is RELATIONSHIP_AWARE when the group is inside the exact
threshold, GREEDY beyond it. Rationale: for the small groups this product is
for, the relationship-aware plan is the one a person would have proposed
("pay back whoever covered you"), and it is exact for its stated objective.
The disclosure block names the strategy in use, so the default is a labeled
choice, not a hidden one.

## Alternatives considered

- **Metrics on the main page.** "3 pagamentos, 0 pares novos" seems harmless,
  but it is the first step of the dashboard slide, and the numbers mean
  nothing before the concepts are introduced. Behind the details tag, the
  same numbers arrive with their explanation.
- **A strategy picker on the main page.** Forces every user to answer a
  question most cannot parse. The picker lives with the comparison, where the
  options are explained.
- **GREEDY as the default everywhere.** Simpler to reason about, but it would
  make the product's most distinctive behavior invisible in the default
  experience, and greedy's plans are occasionally visibly worse (ADR 0008).

## Consequences

Two extra pages to maintain, both read-only derivations with no new state.
`WebFlowTest` asserts the disclosure structure: reasoning present but
collapsed, statement links on balances, enum names absent from the default
page and present on the technical panel.
