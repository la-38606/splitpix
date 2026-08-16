# SplitPix

[![CI](https://github.com/la-38606/splitpix/actions/workflows/ci.yml/badge.svg)](https://github.com/la-38606/splitpix/actions/workflows/ci.yml)

Splitting group expenses in Brazil usually ends with someone doing arithmetic
in a WhatsApp thread at midnight. SplitPix keeps the ledger for you: unequal
expense shares, derived net balances, a minimal set of suggested repayments,
and each recipient's Pix key one copy away. Java 21, Spring Boot, PostgreSQL,
plain SQL. **It does not move money** — the transfer happens in your bank app.

<p>
  <img src="docs/screenshots/group-desktop.png" alt="Group ledger, desktop" width="70%">
  <img src="docs/screenshots/group-mobile.png" alt="Group ledger, mobile" width="24%">
</p>

## How it works

Balances are never stored. Every read derives them from the append-only ledger
with one four-leg SQL aggregate, so "balances sum to zero" is a property of a
query rather than a discipline. Every write in a group serializes on a single
`SELECT ... FOR UPDATE` of the group row; that one decision is what makes
over-settlement impossible under concurrency, and it is cheap because a dinner
group writes at human speed. Retries are safe: idempotency keys plus a request
hash make a same-content retry a replay and a changed-content retry a 409.
The reasoning, with the alternatives I rejected, is in
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

From the demo, unedited (five people, Luiz paid R$ 420,00 with unequal shares;
Bruno, Clara and Diego elided):

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

My concurrency test was lying to me. Two settlement threads started on a
barrier "proved" the group lock worked, and kept passing after I deleted the
lock: in a warm JVM the first transaction committed before the second ever
reached the database. The fix that mattered was to the test, not the code:
hold the lock open inside a paused transaction and assert the second request
is still blocked, plus a race where each settlement is valid alone but
together they over-pay. Deleting the lock now fails the suite deterministically.

## Limitations

- A group is its invite link. Anyone holding the link reads and writes
  everything, stored Pix keys included. No accounts in v1.
- Marking a payment complete is a human assertion; nothing verifies the
  transfer happened.
- The ledger is append-only: amounts are corrected with compensating entries,
  and a mistyped Pix key cannot be edited after the fact.

MIT license.
