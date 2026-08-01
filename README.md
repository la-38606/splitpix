# SplitPix

A Pix-inspired group-expense API for roommates, trips and dinners: it records shared expenses, derives each participant's net balance, minimizes the number of repayments, and tracks which ones were completed.

**SplitPix does not send real Pix payments and is not connected to any bank.** It coordinates the accounting around Pix — it shows you who owes whom, how much, and which Pix key to send it to. The transfer itself happens in your bank app.

The interesting part of this project is not the CRUD. It is the transactional core: derived (never stored) balances, atomic multi-table writes, idempotent retries, row-level locking that makes concurrent settlements provably safe, and a test suite built around accounting invariants rather than endpoint examples.

**Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 16 · plain SQL over `JdbcTemplate` (no ORM) · JUnit 5 + Testcontainers · GitHub Actions

---

## Quick start

Requires **Java 21**, **Docker** (for PostgreSQL, locally and in tests) and `curl` plus `jq` or `python3` for the demo.

```bash
docker compose up -d          # PostgreSQL 16 on :5432
./mvnw spring-boot:run        # API on :8080
./demo.sh                     # full walk-through against the running API
```

Run the tests (Testcontainers starts its own throwaway PostgreSQL — no setup):

```bash
./mvnw verify                 # 149 tests
```

> The schema is applied from `schema.sql` with `CREATE TABLE IF NOT EXISTS`, so it only ever initializes an **empty** database. After pulling a schema change, recreate the volume: `docker compose down -v && docker compose up -d`. Flyway migrations replace this before the app is ever hosted.

## What the demo shows

`./demo.sh` runs the design document's own example — Luiz pays R$ 420,00 for dinner for five people with unequal shares — and then exercises idempotency, settlement and over-settlement rejection. Real output:

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

---

## Architecture

A conventional layered monolith. The layering is boring on purpose; the design effort went into the transaction and concurrency rules.

```
HTTP  ──▶  Controllers      parse, validate shape, map exceptions to status codes
             │
             ▼
           Services         business rules, transaction boundaries, locking, idempotency
             │
             ▼
           Repositories     explicit SQL, row mapping, FOR UPDATE — no business logic
             │
             ▼
           PostgreSQL       constraints as the second line of defense
```

Packages mirror features rather than layers: `group`, `participant`, `expense`, `balance`, `settlement`, `activity`, plus `common` for the error contract and shared validators.

### Money

Every monetary value is an integer number of centavos (`long` / `BIGINT`). Floating point never touches a stored amount. Amounts are capped at 10¹² centavos so a group's aggregate stays representable as a `long` on the way back out.

### Balances are derived, never stored

There is no balance column. A participant's balance is computed on demand by one SQL aggregate with four legs:

```
balance = expenses paid
        − expense shares assigned
        + settlements sent
        − settlements received
```

One source of truth means balance drift is impossible by construction, and the invariant "every group's balances sum to zero" is a property of the query rather than something the application has to maintain.

### Concurrency: one lock per group

Every write inside a group — expense creation, settlement completion, and participant addition — takes the same row lock before doing anything:

```sql
SELECT id FROM groups WHERE id = ? FOR UPDATE;
```

Locking participant rows instead would be insufficient: balances derive from expenses, shares and settlements, so a concurrent expense could commit between a settlement's validation and its insert without touching any participant row, breaking the "cannot over-settle" invariants. Locking the group row serializes every accounting write within one group, which makes the invariants easy to prove and to test. For five-person groups the lost concurrency is irrelevant — this is a deliberate trade of throughput for provable correctness.

Reads (balances, suggestions, history) take no lock. PostgreSQL's default `READ COMMITTED` is sufficient under the group lock, since every balance recalculation happens after the lock is acquired and inside the same transaction.

### Idempotency

Expense and settlement creation both require an `Idempotency-Key` header, unique per group. A retry with the same content returns the original record with `200` instead of creating a second one (a first creation returns `201`), and the uniqueness constraint backs this up in the database.

Each row also stores a SHA-256 of the request, so reusing a key with *different* content is a `409 IDEMPOTENCY_CONFLICT` rather than a silent no-op. Without that, the browser back button — edit a submitted form, submit again — would return the original record and report success while discarding the correction. The hash ignores the order shares are listed in, so the same allocation submitted differently still replays.

### Repayment simplification

Suggested payments are generated on demand from current balances and never stored, so a new expense can never leave a stale obligation behind. The algorithm is greedy — repeatedly match the largest debtor with the largest creditor — which produces at most n−1 transfers in O(n log n). Minimizing the transfer count exactly is NP-hard and buys nothing for real groups.

---

## API

Base path `/api/v1`. Every group-scoped endpoint requires the group's invite token as a query parameter: `?token={inviteToken}`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/groups` | returns `groupId`, `inviteToken`, `creatorParticipantId` |
| `GET` | `/groups/{groupId}?token=` | group with participants |
| `POST` | `/groups/{groupId}/participants?token=` | display name + optional Pix key |
| `POST` | `/groups/{groupId}/expenses?token=` | requires `Idempotency-Key` header |
| `GET` | `/groups/{groupId}/balances?token=` | derived balances, always sum to zero |
| `GET` | `/groups/{groupId}/suggested-payments?token=` | generated on demand |
| `POST` | `/groups/{groupId}/settlements?token=` | requires `Idempotency-Key` header |
| `GET` | `/groups/{groupId}/activity?token=` | expenses + settlements by creation time |
| `GET` | `/ping` | health check |

Create a group:

```bash
curl -X POST localhost:8080/api/v1/groups \
  -H 'Content-Type: application/json' \
  -d '{"groupName":"Jantar no Rio","creatorName":"Luiz",
       "pixKeyType":"EMAIL","pixKeyValue":"luiz@example.com"}'
```

```json
{"groupId":"ac8cb08b-...","inviteToken":"h_pilXOefGd1...","creatorParticipantId":"1f0c..."}
```

Record an expense with exact shares:

```bash
curl -X POST "localhost:8080/api/v1/groups/$GROUP_ID/expenses?token=$TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: despesa-jantar-001' \
  -d '{"description":"Jantar","paidByParticipantId":"'$LUIZ'","totalCents":42000,
       "shares":[{"participantId":"'$LUIZ'","amountCents":7000},
                 {"participantId":"'$ANA'","amountCents":35000}]}'
```

Shares must sum to the total exactly, every participant must belong to the group, and no share may be negative — otherwise the whole request is rejected and nothing is written.

## Browser UI

A server-rendered Thymeleaf UI ships alongside the API at `/` (create a group) and `/g/{groupId}` (the group ledger: balances, suggested payments with copy-the-Pix-key and mark-as-paid, expense and participant forms, history). It is a client of the same services the REST API calls — no business logic lives in the page controllers.

Two details worth knowing:

- **The invite token never appears in a page URL.** Opening `/g/{id}?token=…` exchanges the token for an `HttpOnly`, `SameSite=Lax` cookie scoped to that group's path and redirects to the token-free URL, keeping the only credential in the system out of browser history, `Referer` headers, server access logs, and chat-app link previews. `SameSite=Lax` is also the CSRF defense, which is sufficient here because the cookie is the only credential.
- **Every write is Post/Redirect/Get**, so a refresh cannot resubmit. The expense and mark-as-paid forms additionally carry an idempotency key minted at render time, so an edited resubmission is a conflict rather than lost data; the group and participant forms are PRG-only.

Pix keys are masked in the participant list (`l•••@example.com`) and shown in full only on the payment instruction, where the payer needs to copy them.

### Errors

Every error response is `{"code": "...", "message": "..."}`. Codes are stable English identifiers; messages are Brazilian Portuguese.

| Condition | Status | Code |
|---|---|---|
| Malformed body, bad UUID, missing parameter | 400 | `INVALID_REQUEST` |
| Failed field validation | 400 | `VALIDATION_ERROR` |
| Shares do not sum to the total | 400 | `INVALID_EXPENSE_ALLOCATION` |
| Participant does not belong to the group | 400 | `PARTICIPANT_NOT_IN_GROUP` |
| Missing/blank `Idempotency-Key` | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Wrong invite token | 403 | `INVALID_INVITE_TOKEN` |
| Unknown group | 404 | `GROUP_NOT_FOUND` |
| Pix key already used in the group | 409 | `DUPLICATE_PIX_KEY` |
| Settlement exceeds the current debt | 409 | `SETTLEMENT_EXCEEDS_DEBT` |
| Idempotency key reused with different content | 409 | `IDEMPOTENCY_CONFLICT` |
| Invalid expense total / share / settlement amount | 400 | `INVALID_EXPENSE_TOTAL`, `INVALID_SHARE_AMOUNT`, `INVALID_SETTLEMENT_AMOUNT` |
| Participant repeated in the split | 400 | `DUPLICATE_SHARE_PARTICIPANT` |
| Payer and recipient are the same participant | 400 | `INVALID_SETTLEMENT_PARTICIPANTS` |
| Pix key type and value not given together | 400 | `INVALID_PIX_KEY_PAIR` |
| Wrong method / content type / unknown path | 405 / 415 / 404 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `RESOURCE_NOT_FOUND` |
| Constraint caught behind the service checks | 409 | `CONSTRAINT_VIOLATION` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

---

## Tests

149 tests, all against real PostgreSQL through Testcontainers, run by GitHub Actions on every push to main and every pull request. They are organised around the accounting invariants rather than around endpoints:

- **Zero-sum** — group balances always sum to zero, across multiple expenses and settlements.
- **Allocation** — an expense's shares always equal its total; violations persist nothing.
- **No over-settlement** — a payer can never settle more than they owe, and a recipient can never receive more than they are owed, in either direction.
- **Atomicity** — rollback tests that let the first insert really execute and then fail, proving the transaction unwinds (a test that mocks the insert away would pass vacuously).
- **Idempotency** — repeated keys create exactly one record; the replay returns a body byte-identical to the original.
- **Concurrency** — two settlements that are each individually valid but jointly over-settling: exactly one commits. Plus a deterministic lock test that holds the group lock and proves a second request blocks, rather than relying on thread timing.
- **Simplification** — a randomized property test over 300 seeded zero-sum groups: applying every suggested payment drives all balances to zero, every amount is positive, and nobody pays themselves.
- **Constraints** — the database rejects cross-group references, bad statuses, duplicate idempotency keys and out-of-range amounts even when the service layer is bypassed.

The suite has been audited by mutation: production code was deliberately broken (locks removed, comparisons flipped, SQL legs negated, constraints dropped) to confirm the tests actually fail. Several tests that looked meaningful but passed against broken code were rewritten as a result.

---

## Limitations

These are deliberate MVP boundaries, not oversights:

- **Invite-token access, not authentication.** Anyone holding a group's token can read and write that group. There are no user accounts, no per-participant identity, and no authorization rules.
- **Append-only.** Expenses and settlements are never edited or deleted; a mistaken settlement is corrected with a compensating expense (an opposite settlement would itself be rejected by the over-settlement guard). There is no endpoint to remove a participant, and a participant's Pix key cannot be edited once set.
- **Suggested payments are ephemeral.** They are recomputed per request and go stale the moment anyone records an expense; the client submits payer, recipient and amount when settling.
- **CPF Pix keys are excluded** on purpose — a national identifier behind a link-shared access model is a privacy liability with no upside here. `EMAIL`, `PHONE` and `RANDOM` are supported, and keys are visible to everyone in the group.
- **Schema changes need a fresh database** locally, as noted above.
- **Not hardened for public hosting.** Rate limiting, abuse caps and data-retention rules are catalogued as gaps in the design reference and are not implemented here.

## Language policy

User-facing text — API error messages and `demo.sh` output — is **Brazilian Portuguese**, because the product's audience is Brazilian friend groups. Everything else — code, comments, commit messages, tests, API paths, JSON field names, error `code` values and this README — is **English**, because the engineering audience is international. Machine-readable codes never change with locale, and tests assert on codes, never on message text.

## Design documents

[`docs/design.md`](docs/design.md) is the system design reference: components, boundaries, invariants, and the reasoning behind each load-bearing decision, including the alternatives that were rejected and why. `splitpix_design_doc_v2.pdf` is the original pre-implementation plan, kept for history; where it disagrees with the code, `docs/design.md` is correct.
