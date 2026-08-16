# ADR 0006: JdbcTemplate and hand-written SQL, no JPA

Status: accepted

## Context

The two properties this system depends on most are exactly when a lock is
taken and exactly what the balance aggregate computes. Five tables, no graph
of entities, no lazy loading needs.

## Decision

Plain SQL through `JdbcTemplate` with hand-written `RowMapper`s. Transactions
via `@Transactional` on service methods; locking via an explicit
`SELECT ... FOR UPDATE` statement in `GroupRepository.lockById`.

## Alternatives considered

- **JPA/Hibernate.** Locking becomes `@Lock` annotations and flush-timing
  questions; the balance aggregate becomes JPQL or a native-query escape hatch
  anyway; the persistence context adds failure modes (lazy init, detached
  entities) with no payoff at five tables.
- **jOOQ.** Closest fit philosophically, but adds code generation and a
  dependency for what fourteen hand-written statements cover.

## Consequences

Adding a column touches the record, the mapper and the insert by hand. In
exchange, every statement the system can issue is readable in one repository
file, and the lock is a visible line of SQL rather than framework behavior.
