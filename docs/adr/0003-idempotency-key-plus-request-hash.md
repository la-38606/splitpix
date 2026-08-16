# ADR 0003: Idempotency key per group, plus a request hash

Status: accepted

## Context

Clients retry after timeouts; browsers resubmit forms and offer a back button.
No retry may create a duplicate financial record, and no retry may silently
discard data the user believes was saved.

## Decision

Expense and settlement creation require an `Idempotency-Key`, unique per group
(`UNIQUE (group_id, idempotency_key)` in the schema, checked under the group
lock in the service). Each row also stores a SHA-256 over the request's
meaningful fields (`RequestHashes`; shares sorted before hashing so listing
order is irrelevant). Replay with the same hash returns the stored record with
200; replay with a different hash is a 409 `IDEMPOTENCY_CONFLICT`.

## Alternatives considered

- **Key only, no hash.** The original design. Rejected once the browser UI
  existed: back-button-edit-resubmit reuses the key with different content, and
  returning the original record reports success while dropping the correction.
- **Hash of the raw request bytes.** Whitespace or reordering would produce
  spurious conflicts.
- **Server-generated idempotency tokens.** Moves the retry problem to the
  token-fetch request instead of solving it.

## Consequences

Keys are scoped per group, so two groups can use the same key independently.
The hash must cover every field that changes a request's meaning; adding a
request field means adding it to the hash. The database uniqueness constraint
backs the service check even if the lock is bypassed.
