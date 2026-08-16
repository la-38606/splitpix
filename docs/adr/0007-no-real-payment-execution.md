# ADR 0007: No real payment execution

Status: accepted

## Context

Pix transfers are executed by banks and licensed payment institutions under
Central Bank regulation. An expense-splitting tool does not need to move money
to be useful; it needs to get the accounting right and tell each debtor whom to
pay, how much, and which key to use.

## Decision

SplitPix never initiates a transfer, holds no bank credentials, and cannot
verify that a payment happened. Marking a settlement complete is a human
assertion, recorded append-only. The demo and README state this in the first
lines.

## Alternatives considered

- **Open Finance / PSP integration.** Regulatory and security surface far
  beyond a portfolio project's scope, and it would turn a correctness
  demonstration into an integration demonstration.
- **Pix QR generation without execution.** Harmless, but adds nothing to the
  accounting core; noted as roadmap, not built.

## Consequences

The system's correctness claims are about its own ledger, not about the real
world: a user can mark a payment complete without paying. That is the same
trust model as a shared spreadsheet, made explicit.
