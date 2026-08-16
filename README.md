# SplitPix

[![CI](https://github.com/la-38606/splitpix/actions/workflows/ci.yml/badge.svg)](https://github.com/la-38606/splitpix/actions/workflows/ci.yml)

Splitting group expenses in Brazil tends to end with someone doing arithmetic
in a WhatsApp thread at midnight. SplitPix is a group-expense ledger built
around Pix: exact unequal shares, per-participant net balances, a minimal
repayment plan, and each recipient's Pix key one copy away. Java 21, Spring
Boot, PostgreSQL, hand-written SQL over `JdbcTemplate`. **It does not move
money** — transfers happen in the payer's bank app.

<p>
  <img src="docs/screenshots/group-desktop.png" alt="Group ledger, desktop" width="70%">
  <img src="docs/screenshots/group-mobile.png" alt="Group ledger, mobile" width="24%">
</p>

## How it works

The ledger is append-only and balances are never stored. Every read derives
them with a single four-leg `UNION ALL` aggregate (expenses paid, shares
assigned, settlements sent, settlements received), which makes "balances sum
to zero" a property of the query rather than an invariant the application
must maintain. Every accounting write runs inside one transaction that first
takes `SELECT ... FOR UPDATE` on the group row, so validation and insert are
atomic with respect to every other write in that group; this one lock is what
rules out concurrent over-settlement, and `READ COMMITTED` suffices beneath
it.

Writes are idempotent: keys are unique per `(group, key)` and each row stores
a SHA-256 of the normalized request, so a same-content retry replays with a
byte-identical body and a changed-content retry returns 409 instead of
silently discarding the change. Composite foreign keys on
`(group_id, participant_id)` make cross-group references unrepresentable even
if the service layer is bypassed. Rationale and rejected alternatives:
[docs/design.md](docs/design.md) and [docs/adr/](docs/adr/).

## Run it

Needs Java 21 and Docker (plus `jq` or `python3` for the demo).

```bash
./mvnw spring-boot:test-run   # app on :8080, throwaway Postgres via Testcontainers
./demo.sh                     # second terminal: full walkthrough, pt-BR
```

The browser UI is at http://localhost:8080. `./mvnw verify` runs the 170-test
suite; every test that touches persistence runs against real PostgreSQL.

## Example

Demo output, unedited (five people, Luiz paid R$ 420,00 with unequal shares;
three participants elided):

```
== 5. Saldos
  Luiz        R$ 350,00
  Ana         -R$ 90,00
  TOTAL         R$ 0,00  <- a soma dos saldos é sempre zero

== 8. Tentativa de pagar mais do que se deve
HTTP 409
{"code":"SETTLEMENT_EXCEEDS_DEBT","message":"O valor do pagamento excede a dívida atual."}
```

Full API, error codes and curl examples: [docs/api.md](docs/api.md).

## The hardest problem

The subtlest defect was a concurrency test that proved nothing. Two
settlement threads released by a barrier appeared to demonstrate the group
lock, and kept passing after the lock was deleted: in a warm JVM the first
transaction commits before the second ever reaches the database. The
replacement (`GroupLockTest`) holds the lock open inside a paused transaction
and asserts that a second request is still blocked, alongside a two-thread
race in which each settlement is valid alone but the pair jointly over-pays.
Deleting the lock now fails the suite deterministically.

## Limitations

- A group is its invite link. Anyone holding the link reads and writes
  everything, stored Pix keys included. No accounts in v1.
- Marking a payment complete is a human assertion; nothing verifies the
  transfer happened.
- The ledger is append-only: amounts are corrected with compensating entries,
  and a mistyped Pix key cannot be edited after the fact.

MIT license.
