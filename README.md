# SplitPix

[![CI](https://github.com/la-38606/splitpix/actions/workflows/ci.yml/badge.svg)](https://github.com/la-38606/splitpix/actions/workflows/ci.yml)

SplitPix tracks a group's shared expenses and works out who should pay whom
over Pix. The same balances usually admit several valid repayment plans, so
settlement is treated as a choice among objectives: fewest payments, fewest
new payer→recipient relationships, or a fast heuristic, each labeled with
what it guarantees. Underneath, balances are derived from an append-only
ledger, and the accounting invariants hold under concurrent writes and
retries. **It does not move money.** Every suggested payment carries the
recipient's Pix key; the transfer happens in the payer's bank app.

<p>
  <img src="docs/screenshots/group-desktop.png" alt="Group ledger, desktop" width="70%">
  <img src="docs/screenshots/group-mobile.png" alt="Group ledger, mobile" width="24%">
</p>

## Demo

One minute of the real application on synthetic data: expenses, balances, a
suggested plan, the statement behind a balance, three strategies disagreeing
about the same balances, and a payment recorded over Pix.

https://github.com/user-attachments/assets/62cbc9ee-f34e-48e0-864e-d9afc7935271

Regenerate locally:

```bash
./scripts/record-demo.sh
```

## Why build another expense splitter?

Splitting one bill is arithmetic. Keeping a month of shared expenses correct
is a concurrency and accounting problem wearing a consumer UI: requests get
retried, two people hit save at the same moment, and last week's typo needs
a correction that doesn't rewrite history. Solve all of that and a second
problem is still standing. Who should actually pay whom?

The balances alone don't decide it. Take five people after a trip, with net
positions +500, +400, −400, −300, −200 (the demo's `--tecnico` act builds
this exact group):

| Strategy | Payments | New payment pairs | Guarantee |
|---|---|---|---|
| Fast (greedy) | 4 | 3 | none |
| Fewest payments | 3 | 0 | provably minimal |
| Prefer existing relationships | 3 | 0 | provably minimal for that objective |

All three plans zero every balance exactly. What differs is the shape of the
settlement graph. The greedy plan asks Elisa to pay Bruno, two people who
never shared an expense; three transfers settle the same group along the
lines the expenses already drew. The objectives can genuinely conflict, too:
on other balance vectors the relationship-aware plan spends an extra payment
to avoid creating a single awkward pair. Whether that trade is worth it
depends on the group, so SplitPix computes all the plans and lets the group
choose.

Pix is the last mile: every suggested payment ends in a copyable Pix key,
and the interface is in Portuguese for its Brazilian audience. The
accounting is the part worth building software for, because in Brazil the
transfer itself is already instant and free.

## How it works

```
expenses (append-only ledger) → derived balances → settlement optimizer → Pix payment instructions
```

Balances are never stored. Every read derives them from a four-leg aggregate
over expenses paid, shares assigned, settlements sent and received; "balances
sum to zero" is a property of the query. Writes serialize per group on a
PostgreSQL row lock (`SELECT ... FOR UPDATE` on the group row), which is why
two concurrent settlements can never jointly overpay a debt. Idempotency
keys plus a SHA-256 request hash make retries safe: same content replays
byte-identically, and a back-button edit surfaces as a 409. Composite
foreign keys mean a row physically cannot reference a participant from
another group. Money is integer arithmetic end to end; floating point never
touches an amount. All persistence is hand-written SQL.

The optimizer is a separate pure layer. Both exact strategies run one
memoized search over basic plans; seeded property tests cross-check it
against an independent subset-DP oracle and against exhaustive enumeration,
and a production invariant checker re-applies every plan before it leaves
the service. Past ten nonzero balances (eight when an amount cap is set) the
exact strategies answer 400 instead of quietly handing back a heuristic.
Both limits came from benchmarking, with a deterministic search budget
behind them.

Requests can also carry constraints: forbid a payer→recipient pair, or cap
the amount a single payer→recipient payment may carry (a plan holds one
payment per pair, so a cap is never met by splitting a debt into
installments). When nothing satisfies them the answer is a 409
`NO_FEASIBLE_SETTLEMENT_PLAN`. Algorithms, proofs and worked examples:
[docs/design.md §10](docs/design.md).

## Explainable, on demand

The interface stays plain: balances, suggested payments, Pix keys, history.
Anything deeper is behind a deliberate click. Each balance links to a
statement of the ledger entries behind it, and the service refuses to serve
a statement whose entries do not sum back to the balance. The suggested plan
carries a collapsed explanation naming the strategy in use and its
guarantee, and a comparison page shows all strategies side by side with an
advanced form for constraints:

<img src="docs/screenshots/planos-desktop.png" alt="Strategy comparison" width="70%">

## Run it

Needs Java 21 and Docker (plus `jq` or `python3` for the demo).

```bash
./mvnw spring-boot:test-run   # app on :8080, throwaway Postgres via Testcontainers
./demo.sh                     # second terminal: the consumer walkthrough
./demo.sh --tecnico           # adds strategy comparison, constraints, infeasibility
```

The browser UI is at http://localhost:8080. `./mvnw verify` runs the
204-test suite; every test that touches persistence runs against real
PostgreSQL. Full API with curl examples: [docs/api.md](docs/api.md).
Decisions and their rejected alternatives: [docs/adr/](docs/adr/).
Printable design document:
[docs/splitpix-design-doc.pdf](docs/splitpix-design-doc.pdf).

## Scope and privacy

This is a portfolio project, not a payment service. Nothing here executes or
verifies a transfer; marking a payment complete is a claim by the person who
made it, and every demo runs on synthetic identities: no CPF, no bank
account, no real contact data required anywhere.

One deliberate omission: Pix accepts the CPF, Brazil's national ID, as a key
type, but a system whose only access control is a shareable invite link has
no business storing a lifelong identifier, so that key type does not exist
here. The full reasoning, LGPD included, is in
[ADR 0004](docs/adr/0004-no-cpf-pix-keys.md).

## Limitations

- A group is its invite link. Anyone holding it can read and write
  everything, stored Pix keys included. No accounts in v1.
- Append-only means append-only: amounts get corrected with compensating
  entries, and a mistyped Pix key stays wrong.
- Exact optimization stops at ten nonzero balances (eight with an amount
  cap). Larger groups get the greedy plan by default, and a 400 if they ask
  for an exact strategy outright.

MIT license.
