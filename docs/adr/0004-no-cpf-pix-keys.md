# ADR 0004: CPF Pix keys are excluded

Status: accepted

## Context

Pix supports five key types; one of them is the CPF, Brazil's national taxpayer
identifier. SplitPix groups are protected by a shared invite link, not by
authentication: everyone in a group sees every stored key.

## Decision

`PixKeyType` has three constants: `EMAIL`, `PHONE`, `RANDOM`. CPF (and CNPJ) do
not exist in the enum, so a CPF cannot be stored as a typed key. Per-type shape
validation (`PixKeys.validateFormat`) narrows smuggling: `RANDOM` must be a
UUID, so bare digits never pass; `PHONE` must be E.164 with a leading `+`.

## Alternatives considered

- **Support CPF like any other key.** A national identifier behind a
  link-shared access model is a privacy liability with no compensating benefit;
  anyone with the link could harvest CPFs.
- **Support CPF but mask it.** The payer needs the full key to make the
  transfer, so masking defeats the purpose exactly where it matters.

## Consequences

Users whose only Pix key is their CPF must register an email, phone or random
key to receive through SplitPix. A CPF prefixed with `+` under `PHONE` is
indistinguishable from a phone number and passes shape validation; this is
recorded as a limitation rather than pretended away.
