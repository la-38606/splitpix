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
| `GET` | `/groups/{id}/suggested-payments` | generated on demand, never stored |
| `POST` | `/groups/{id}/settlements` | `Idempotency-Key` required |
| `GET` | `/groups/{id}/activity` | expenses + settlements, newest last |
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
