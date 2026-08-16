# SplitPix

Splitting group expenses in Brazil usually ends in a WhatsApp thread full of
screenshots and someone doing arithmetic at midnight. I built SplitPix to fix
the part that is actually hard: keeping the accounting exact, concurrent-safe,
and easy to settle over Pix. It records shared expenses with unequal shares,
derives each person's net balance, boils the debts down to a minimal set of
transfers, and hands each debtor the recipient's Pix key and the exact amount.

**SplitPix does not send real Pix payments and is not connected to any bank.**
The transfer happens in your bank app; SplitPix keeps the ledger honest.

The interesting part is not the CRUD. It is the transactional core: balances
derived instead of stored, one row lock serializing every write in a group,
idempotency keys backed by request hashing, and a test suite built around
accounting invariants instead of endpoint examples.

**Stack:** Java 21, Spring Boot 4.1, PostgreSQL 16, plain SQL over
`JdbcTemplate`, JUnit 5 + Testcontainers, GitHub Actions.

## Run it in under a minute

Requires Java 21 and Docker (plus `jq` or `python3` for the demo script).

```bash
./mvnw spring-boot:test-run     # app on :8080, Testcontainers provides the DB
./demo.sh                       # in a second terminal: full walkthrough
```

No database setup at all: `test-run` boots the app against a throwaway
PostgreSQL container. For a persistent local database use
`docker compose up -d && ./mvnw spring-boot:run` instead. The browser UI is at
http://localhost:8080.

Tests:

```bash
./mvnw verify                   # 166 tests against real PostgreSQL
```

One sharp edge: the schema is applied with `CREATE TABLE IF NOT EXISTS`, so it
only initializes an empty database. After pulling a schema change, recreate
the compose volume with `docker compose down -v`. Tests are unaffected (fresh
database every run).

## What the demo shows

`demo.sh` walks the canonical example: Luiz pays R$ 420,00 for dinner for five
people with unequal shares. Then it replays a request to show idempotency,
settles one debt, and tries to over-pay. Real output, unedited:

```
== 1. Criando o grupo
Grupo criado (HTTP 201): ac8cb08b-68f3-4278-a5b4-060dba7c93a3
Token de convite: h_pilXOefGd1...

== 2. Adicionando participantes
  Ana    adicionado(a) (HTTP 201) — chave PHONE: +5511999990001
  Bruno  adicionado(a) (HTTP 201) — chave EMAIL: bruno@example.com
  Clara  adicionado(a) (HTTP 201) — chave RANDOM: a1b2c3d4-0000-4000-8000-000000000001
  Diego  adicionado(a) (HTTP 201) — chave PHONE: +5511999990004

== 3. Registrando a despesa (R$ 420,00, divisão desigual)
Despesa registrada (HTTP 201): Luiz pagou R$ 420,00

== 4. Reenviando a mesma requisição (idempotência)
Mesma chave de idempotência: HTTP 200 (200 = despesa existente, nada duplicado)

== 5. Saldos
  Luiz        R$ 350,00
  Ana         -R$ 90,00
  Bruno       -R$ 80,00
  Clara       -R$ 60,00
  Diego      -R$ 120,00
  TOTAL         R$ 0,00  <- a soma dos saldos é sempre zero

== 6. Pagamentos sugeridos
  Diego  paga  R$ 120,00 para Luiz   (chave Pix: luiz@example.com)
  Ana    paga   R$ 90,00 para Luiz   (chave Pix: luiz@example.com)
  Bruno  paga   R$ 80,00 para Luiz   (chave Pix: luiz@example.com)
  Clara  paga   R$ 60,00 para Luiz   (chave Pix: luiz@example.com)

== 7. Ana confirma o pagamento de R$ 90,00
Pagamento registrado (HTTP 201)
  Luiz        R$ 260,00
  Ana           R$ 0,00

== 8. Tentativa de pagar mais do que se deve
HTTP 409
{"code":"SETTLEMENT_EXCEEDS_DEBT","message":"O valor do pagamento excede a dívida atual."}

== 9. Histórico
  EXPENSE        R$ 420,00
  SETTLEMENT      R$ 90,00

Fim da demonstração. Nenhum pagamento Pix real foi feito.
```

## Architecture

A layered monolith on purpose. The layering is boring; the design effort went
into transactions and concurrency.

```
HTTP  ──▶  Controllers      parse, validate shape, map exceptions to statuses
             │
             ▼
           Services         business rules, transactions, locking, idempotency
             │
             ▼
           Repositories     hand-written SQL, row mapping, FOR UPDATE
             │
             ▼
           PostgreSQL       constraints as the second line of defense
```

Packages mirror features, not layers: `group`, `participant`, `expense`,
`balance`, `settlement`, `activity`, plus `common` (error contract, shared
validators) and `web` (a server-rendered Thymeleaf UI that calls the same
services the REST API calls). The full system reference with every decision's
rationale is in [docs/design.md](docs/design.md).

## Key decisions

Each of these has a full ADR in [docs/adr/](docs/adr/) with the alternatives I
considered and rejected.

- **Balances are derived, never stored**
  ([ADR 0002](docs/adr/0002-append-only-ledger-derived-balances.md)). One SQL
  aggregate with four legs computes every balance on demand. There is no
  balance column to drift, and "balances sum to zero" is a property of the
  query. The ledger is append-only; a mistaken settlement is corrected with a
  compensating expense, and there is a test proving the recipe works.

- **One lock per group, on every write**
  ([ADR 0001](docs/adr/0001-group-row-lock-on-every-write.md)). Locking
  participant rows would be unsound, not just slow: an expense insert changes
  balances without touching any participant row. `SELECT ... FOR UPDATE` on
  the group row serializes all accounting writes in a group, which makes the
  no-over-settlement invariants provable in a paragraph. Throughput inside one
  group is the accepted cost; a dinner group never notices.

- **Idempotency keys plus request hashing**
  ([ADR 0003](docs/adr/0003-idempotency-key-plus-request-hash.md)). Retrying
  a write with the same key and content returns the original record. Reusing
  a key with different content is a 409, because the browser back button makes
  that case routine, and silently discarding a correction is data loss.

- **CPF keys excluded**
  ([ADR 0004](docs/adr/0004-no-cpf-pix-keys.md)). Groups are link-shared, so
  every member sees every key. A national ID does not belong in that model.
  The enum simply has no CPF constant, and shape validation keeps CPF-like
  values out of the other types where possible.

- **Greedy simplification, computed on demand**
  ([ADR 0005](docs/adr/0005-greedy-debt-simplification.md)). At most n-1
  transfers in O(n log n). Exact minimization is NP-hard and changes nothing
  for a dinner group. Suggestions are never stored, so they cannot go stale in
  the database; the settlement path re-validates against live balances anyway.

- **JdbcTemplate, no JPA**
  ([ADR 0006](docs/adr/0006-jdbctemplate-over-jpa.md)). The two properties
  everything depends on are when the lock is taken and what the aggregate
  computes. Both are visible lines of SQL here, not framework behavior.

- **No real payments**
  ([ADR 0007](docs/adr/0007-no-real-payment-execution.md)). Deliberate scope,
  stated everywhere: the trust model is a shared spreadsheet, made explicit
  and made concurrent-safe.

## The browser UI

Server-rendered Thymeleaf at `/`: create a group, share the invite link, add
participants and expenses, copy Pix keys, mark payments complete. Two details
worth knowing:

- The invite token never appears in a page URL. Opening an invite link
  exchanges the token for an `HttpOnly`, `SameSite=Lax` cookie scoped to the
  group's path, then redirects to a token-free URL. That keeps the only
  credential in the system out of history, logs, and chat-app link previews;
  `SameSite=Lax` doubles as the CSRF defense since there is no other
  credential.
- Every form is Post/Redirect/Get. The expense and mark-as-paid forms carry an
  idempotency key minted at render time, so an edited resubmission is a 409
  instead of silent loss.

Pix keys render masked in the participant list (`l•••@example.com`) and in
full only on the payment instruction, where the payer needs to copy them.

## Errors

Every error is `{"code": "...", "message": "..."}`. Codes are stable English
identifiers; messages are Brazilian Portuguese, because the audience is
Brazilian friend groups while the engineering surface stays English. Tests
assert on codes, never on message text.

| Condition | Status | Code |
|---|---|---|
| Malformed body, bad UUID, missing parameter | 400 | `INVALID_REQUEST` |
| Failed field validation | 400 | `VALIDATION_ERROR` |
| Shares do not sum to the total | 400 | `INVALID_EXPENSE_ALLOCATION` |
| Participant does not belong to the group | 400 | `PARTICIPANT_NOT_IN_GROUP` |
| Invalid expense total / share / settlement amount | 400 | `INVALID_EXPENSE_TOTAL`, `INVALID_SHARE_AMOUNT`, `INVALID_SETTLEMENT_AMOUNT` |
| Participant repeated in the split | 400 | `DUPLICATE_SHARE_PARTICIPANT` |
| Payer and recipient are the same participant | 400 | `INVALID_SETTLEMENT_PARTICIPANTS` |
| Pix key type and value not given together | 400 | `INVALID_PIX_KEY_PAIR` |
| Pix key does not match its type's shape | 400 | `INVALID_PIX_KEY_FORMAT` |
| Missing or blank `Idempotency-Key` | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Wrong invite token | 403 | `INVALID_INVITE_TOKEN` |
| Unknown group | 404 | `GROUP_NOT_FOUND` |
| Pix key already used in the group | 409 | `DUPLICATE_PIX_KEY` |
| Settlement exceeds the current debt | 409 | `SETTLEMENT_EXCEEDS_DEBT` |
| Idempotency key reused with different content | 409 | `IDEMPOTENCY_CONFLICT` |
| Wrong method / content type / unknown path | 405 / 415 / 404 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `RESOURCE_NOT_FOUND` |
| Constraint caught behind the service checks | 409 | `CONSTRAINT_VIOLATION` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

The API itself: base path `/api/v1`, invite token as `?token=` on every
group-scoped call, `Idempotency-Key` header on the two money-writing POSTs.
Endpoints: `POST /groups`, `GET /groups/{id}`, `POST /groups/{id}/participants`,
`POST /groups/{id}/expenses`, `GET /groups/{id}/balances`,
`GET /groups/{id}/suggested-payments`, `POST /groups/{id}/settlements`,
`GET /groups/{id}/activity`, `GET /ping`.

## Testing

166 tests, every one against real PostgreSQL through Testcontainers, run by
GitHub Actions on every push to main and every pull request. H2 would have
been faster and would have proven nothing: the system leans on `FOR UPDATE`
blocking, deferrable foreign keys, CHECK constraints and PostgreSQL's
aggregate typing, none of which an in-memory imitation reproduces.

The suite is organized around invariants, not endpoints:

- Balances always sum to zero, across expenses and settlements.
- Shares always equal their expense's total; violations persist nothing.
- Nobody can settle more than they owe or receive more than they are owed,
  proven by a genuine two-thread race where each request is valid alone and
  only the lock prevents joint over-settlement.
- Rollback tests let the first insert really execute before failing, so they
  prove the transaction unwinds instead of passing vacuously.
- Replays are byte-identical to the original response; key reuse with
  different content conflicts.
- A 300-seed property test on the simplifier: applying every suggestion zeroes
  every balance, all amounts positive, nobody pays themselves.
- Direct-SQL tests prove the database constraints reject bad rows even when
  the service layer is bypassed.

The suite has been audited by mutation testing: I broke the production code on
purpose (removed locks, flipped comparisons, negated SQL legs, dropped
constraints) and rewrote every test that stayed green. Several tests that
looked meaningful failed that audit and were replaced with ones that fail
against broken code.

## Known limitations

- Invite link access, not authentication. Anyone with the link reads and
  writes that group, including stored Pix keys. Real accounts are out of
  scope for v1.
- Marking a payment complete is a human assertion; nothing verifies a
  transfer happened (see ADR 0007).
- Append-only means no editing: a wrong Pix key cannot be corrected once set,
  and participants cannot be removed. The correction path for amounts is a
  compensating expense.
- A CPF disguised with `+` as a PHONE key passes shape validation; it is not
  distinguishable from a real phone number.
- Group and participant creation are not idempotent (the two money paths are).
- Schema evolution needs a fresh database locally; Flyway is the planned fix
  before any hosted deployment.
- No rate limiting. `POST /groups` is anonymous by design, so a public
  deployment needs per-IP limits first.

## Roadmap

Flyway migrations, per-IP rate limiting and a data-retention policy (the
hosting preconditions, in that order), then Pix QR payload generation and
CSV export. Each lands with the same testing bar as the core.

## License

MIT. See [LICENSE](LICENSE).
