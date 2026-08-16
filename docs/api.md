# API reference

Base path `/api/v1`. Every group-scoped call takes the group's invite token as
`?token={inviteToken}`. The two money-writing POSTs also require an
`Idempotency-Key` header.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/groups` | returns `groupId`, `inviteToken`, `creatorParticipantId` |
| `GET` | `/groups/{id}` | group with participants |
| `POST` | `/groups/{id}/participants` | display name + optional Pix key |
| `POST` | `/groups/{id}/expenses` | `Idempotency-Key` required |
| `GET` | `/groups/{id}/balances` | derived balances, always sum to zero |
| `GET` | `/groups/{id}/settlement-plan` | `?strategy=` optional, default `GREEDY` |
| `POST` | `/groups/{id}/settlement-plan` | strategy + constraints in the body; computes, writes nothing |
| `GET` | `/groups/{id}/settlement-plan/compare` | every strategy for one snapshot |
| `GET` | `/groups/{id}/participants/{pid}/balance-explanation` | the ledger legs behind one balance |
| `POST` | `/groups/{id}/settlements` | `Idempotency-Key` required |
| `GET` | `/groups/{id}/activity` | the ledger: expenses + settlements with sequence numbers |
| `GET` | `/ping` | health check |

## Example

```bash
curl -X POST localhost:8080/api/v1/groups \
  -H 'Content-Type: application/json' \
  -d '{"groupName":"Jantar no Rio","creatorName":"Luiz",
       "pixKeyType":"EMAIL","pixKeyValue":"luiz@example.com"}'
```

```json
{"groupId":"ac8cb08b-...","inviteToken":"h_pilXOefGd1...","creatorParticipantId":"1f0c..."}
```

Record an expense with exact shares (must sum to the total, every participant
must belong to the group, no negative shares — otherwise nothing is written):

```bash
curl -X POST "localhost:8080/api/v1/groups/$GROUP/expenses?token=$TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: despesa-jantar-001' \
  -d '{"description":"Jantar","paidByParticipantId":"'$LUIZ'","totalCents":42000,
       "shares":[{"participantId":"'$LUIZ'","amountCents":7000},
                 {"participantId":"'$ANA'","amountCents":35000}]}'
```

## Settlement plans

A plan is a derivation, never a stored object: current balances plus a
strategy (plus optional constraints) in, a list of payment instructions out.
Repeating the request against an unchanged ledger returns the identical plan;
that is why the POST needs no idempotency key. Every response carries
`ledgerRevision` — the count of accounting entries (expenses plus completed
settlements) the plan was derived from. If the group's revision has moved on,
regenerate.

Three strategies:

| Strategy | Objective | Guarantee | Size limit (nonzero balances) |
|---|---|---|---|
| `GREEDY` | short plan, fast | at most n−1 transfers; **no optimality claim** | none |
| `MIN_TRANSFERS` | fewest transfers | provably minimal (`exact: true`) | 10 (8 with a cap) |
| `RELATIONSHIP_AWARE` | fewest new payment pairs, then fewest transfers | provably minimal for that order | 10 (8 with a cap) |

`exact: true` means proven optimal for the strategy's declared objective —
the search is exhaustive over the plan space, cross-checked in tests against
brute force. `GREEDY` always reports `exact: false`. Past the size limit the
exact strategies answer 400 `UNSUPPORTED_OPTIMIZATION_SIZE`; they never fall
back silently.

```bash
curl "localhost:8080/api/v1/groups/$GROUP/settlement-plan?token=$TOKEN&strategy=MIN_TRANSFERS"
```

```json
{
  "groupId": "ac8cb08b-...",
  "ledgerRevision": 2,
  "plan": {
    "strategy": "MIN_TRANSFERS",
    "exact": true,
    "transferCount": 3,
    "novelRelationshipEdges": 0,
    "totalAmountCents": 90000,
    "transfers": [
      {"payerParticipantId": "...", "payerName": "Clara",
       "recipientParticipantId": "...", "recipientName": "Bruno",
       "recipientPixKey": "bruno@example.com",
       "amountCents": 40000, "novelRelationship": false}
    ]
  }
}
```

`novelRelationship` marks a transfer between two people with no prior
financial relationship in the group (no shared expense, no previous
settlement — the exact rule is ADR 0010).

### Constraints

Constraints go through the POST body and work with the two exact strategies
only; combined with `GREEDY` they are a 400 `INVALID_SETTLEMENT_CONSTRAINT`.

```bash
curl -X POST "localhost:8080/api/v1/groups/$GROUP/settlement-plan?token=$TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"strategy": "MIN_TRANSFERS",
       "constraints": {
         "forbiddenPairs": [
           {"payerParticipantId": "'$DIEGO'", "recipientParticipantId": "'$ANA'"}
         ],
         "maxTransferCents": 25000}}'
```

- `forbiddenPairs` is directed: forbidding Diego→Ana still allows Ana→Diego.
- `maxTransferCents` is the maximum amount allowed on a single
  payer→recipient edge. A plan holds at most one instruction per pair — a
  debt is never split into installments to the same recipient — so a cap
  below a two-person debt makes settlement genuinely infeasible.
- Constraints that admit no plan are a 409 `NO_FEASIBLE_SETTLEMENT_PLAN`.

### Comparison

```bash
curl "localhost:8080/api/v1/groups/$GROUP/settlement-plan/compare?token=$TOKEN"
```

Returns `plans` (each strategy's plan for the same `REPEATABLE_READ`
snapshot) and `skipped` (strategies the group size rules out, with the error
code as `reason`).

## Balance explanation

```bash
curl "localhost:8080/api/v1/groups/$GROUP/participants/$ANA/balance-explanation?token=$TOKEN"
```

```json
{
  "groupId": "ac8cb08b-...",
  "participantId": "...",
  "displayName": "Ana",
  "balanceCents": -15000,
  "entries": [
    {"type": "EXPENSE_SHARE", "sourceId": "...", "description": "Jantar",
     "counterpartyParticipantId": "...", "counterpartyName": "Luiz",
     "amountCents": -35000, "createdAt": "2026-08-16T..."},
    {"type": "SETTLEMENT_SENT", "sourceId": "...", "description": null,
     "counterpartyParticipantId": "...", "counterpartyName": "Luiz",
     "amountCents": 20000, "createdAt": "2026-08-16T..."}
  ]
}
```

Entry types are the four legs of the balance derivation: `EXPENSE_PAID`
(+total), `EXPENSE_SHARE` (−share), `SETTLEMENT_SENT` (+amount),
`SETTLEMENT_RECEIVED` (−amount). The entries always sum to `balanceCents`;
the service verifies that equality on every request and fails with a 500
rather than return a statement that does not reconcile. Zero shares are
omitted.

## Ledger

`GET /groups/{id}/activity` returns the append-only ledger in serialization
order. Each item carries a dense 1-based `sequence`; the response's
`ledgerRevision` is the highest sequence. Settlement suggestions are not
ledger entries — only recorded expenses and completed settlements are.

## Idempotency

Retrying with the same key and the same content returns the original record
with 200 (first creation is 201), byte-identical body. Reusing a key with
different content is a 409 `IDEMPOTENCY_CONFLICT`: each row stores a SHA-256
of the request's meaning (share order and description whitespace are
normalized first), so a back-button edit-and-resubmit surfaces as a conflict
instead of silently discarding the correction. Keys are scoped per group.

## Errors

Every error is `{"code": "...", "message": "..."}`. Codes are stable English
identifiers; messages are Brazilian Portuguese. Tests assert on codes, never
on message text (the bundle itself is guarded by its own test).

| Condition | Status | Code |
|---|---|---|
| Malformed body, bad UUID, missing parameter, unknown strategy | 400 | `INVALID_REQUEST` |
| Failed field validation | 400 | `VALIDATION_ERROR` |
| Shares do not sum to the total | 400 | `INVALID_EXPENSE_ALLOCATION` |
| Participant does not belong to the group | 400 | `PARTICIPANT_NOT_IN_GROUP` |
| Invalid expense total / share / settlement amount | 400 | `INVALID_EXPENSE_TOTAL`, `INVALID_SHARE_AMOUNT`, `INVALID_SETTLEMENT_AMOUNT` |
| Participant repeated in the split | 400 | `DUPLICATE_SHARE_PARTICIPANT` |
| Payer and recipient are the same participant | 400 | `INVALID_SETTLEMENT_PARTICIPANTS` |
| Pix key type and value not given together | 400 | `INVALID_PIX_KEY_PAIR` |
| Pix key does not match its type's shape | 400 | `INVALID_PIX_KEY_FORMAT` |
| Missing or blank `Idempotency-Key` | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Group too large for an exact strategy | 400 | `UNSUPPORTED_OPTIMIZATION_SIZE` |
| Constraints invalid or combined with `GREEDY` | 400 | `INVALID_SETTLEMENT_CONSTRAINT` |
| Wrong invite token | 403 | `INVALID_INVITE_TOKEN` |
| Unknown group | 404 | `GROUP_NOT_FOUND` |
| Pix key already used in the group | 409 | `DUPLICATE_PIX_KEY` |
| Settlement exceeds the current debt | 409 | `SETTLEMENT_EXCEEDS_DEBT` |
| No plan satisfies the requested constraints | 409 | `NO_FEASIBLE_SETTLEMENT_PLAN` |
| Idempotency key reused with different content | 409 | `IDEMPOTENCY_CONFLICT` |
| Wrong method / content type / unknown path | 405 / 415 / 404 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `RESOURCE_NOT_FOUND` |
| Constraint caught behind the service checks | 409 | `CONSTRAINT_VIOLATION` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |
