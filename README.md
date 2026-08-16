# SplitPix

[![CI](https://github.com/la-38606/splitpix/actions/workflows/ci.yml/badge.svg)](https://github.com/la-38606/splitpix/actions/workflows/ci.yml)

Splitting group expenses in Brazil tends to end with someone doing arithmetic
in a WhatsApp thread at midnight. SplitPix keeps the ledger instead: exact
unequal shares, running net balances, a minimal repayment plan, and each
recipient's Pix key one copy away. Java 21, Spring Boot, PostgreSQL,
hand-written SQL over `JdbcTemplate`. **It does not move money.** Transfers
happen in the payer's bank app.

<p>
  <img src="docs/screenshots/group-desktop.png" alt="Group ledger, desktop" width="70%">
  <img src="docs/screenshots/group-mobile.png" alt="Group ledger, mobile" width="24%">
</p>

## Background

Pix is Brazil's instant payment system, run by the Central Bank since 2020.
Transfers are free for individuals and settle in seconds, any hour, any day.
A recipient is addressed by a key: an email address, a phone number, or a
random UUID. By now it is simply how Brazilians pay each other. The payment
half of splitting a bill is solved.

The accounting half is not. A trip or a shared apartment piles up dozens of
expenses with unequal shares, and the group ends up with a web of pairwise
debts nobody wrote down. Who covered the market run? Was the R$ 420,00 dinner
split evenly or by consumption? The usual tool is a spreadsheet, or memory,
and both give out at the same point: nobody can name the exact transfer that
settles everyone up.

That half is what SplitPix owns. A group records each expense with exact
per-person shares; the service derives every member's balance and boils the
debt web down to at most n−1 suggested transfers, each with the recipient's
key and the exact amount attached. Completed payments get recorded, so the
plan shrinks as people pay. One deliberate omission: CPF keys. Brazil's
national ID doubles as a Pix key type, and everyone in a group can see stored
keys, so that type simply does not exist here
([ADR 0004](docs/adr/0004-no-cpf-pix-keys.md)).

## How it works

The ledger is append-only. Balances are never stored anywhere; every read
derives them on the spot with a single four-leg `UNION ALL` aggregate over
expenses paid, shares assigned, settlements sent and settlements received.
"Balances sum to zero" is a property of that query. No application code has
to keep it true.

Writes serialize per group. Every accounting write begins with
`SELECT ... FOR UPDATE` on the group row, inside the same transaction that
validates and inserts, which is why two concurrent settlements can never
jointly overpay a debt. `READ COMMITTED` is enough underneath that lock.
Retries are safe to send twice: idempotency keys are unique per
`(group, key)` and each row stores a SHA-256 of the normalized request, so a
same-content retry replays with a byte-identical body while a changed-content
retry gets a 409. And composite foreign keys on `(group_id, participant_id)`
mean a row physically cannot point at a participant from another group, even
when the service layer is bypassed. Rationale and rejected alternatives:
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
settlement threads released by a barrier looked like proof that the group
lock worked. Then the lock was deleted, and the test kept passing: in a warm
JVM the first transaction commits before the second ever reaches the
database. The replacement (`GroupLockTest`) holds the lock open inside a
paused transaction and checks that a second request is still stuck waiting.
A companion race runs two settlements that are each valid alone but overpay
together. Delete the lock now and the suite fails, every run.

## Limitations

- A group is its invite link. Anyone holding it can read and write
  everything, stored Pix keys included. No accounts in v1.
- Marking a payment complete is a claim, not a verification. SplitPix has no
  way to know whether money actually moved.
- Append-only means append-only: amounts get corrected with compensating
  entries, and a mistyped Pix key stays wrong.

MIT license.
