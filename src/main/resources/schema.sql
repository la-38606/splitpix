-- SplitPix schema (design doc v2.1 section 10, hardened after adversarial review).
-- Applied idempotently at startup via spring.sql.init; converted to Flyway V1__init.sql before hosting (addendum 35.4).
-- All monetary values are integer centavos (BIGINT). Floating point is never used for money.
--
-- Hardening beyond the doc's schema:
--   * UNIQUE (group_id, id) on participants and expenses enables composite FKs,
--     so no row can ever reference a participant or expense of another group.
--   * expense_shares carries group_id for those composite FKs; its participant FK
--     is DEFERRABLE INITIALLY DEFERRED: group deletion cascades cleanly (nothing
--     dangles at commit) while a lone participant delete still fails instead of
--     silently corrupting the zero-sum invariant.
--   * Amounts are capped at 10^12 centavos: unbounded BIGINTs let two absurd
--     expenses overflow the balance aggregate and poison the group's reads.
--   * pix_key_type is CHECK-constrained; an out-of-enum row would otherwise break
--     every read of its group at the RowMapper.

CREATE TABLE IF NOT EXISTS groups (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    invite_token VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS participants (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    pix_key_type VARCHAR(20),
    pix_key_value VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT valid_pix_key_type CHECK (
        pix_key_type IN ('EMAIL', 'PHONE', 'RANDOM')
    ),

    CONSTRAINT valid_pix_key_pair CHECK (
        (pix_key_type IS NULL AND pix_key_value IS NULL)
        OR
        (pix_key_type IS NOT NULL AND pix_key_value IS NOT NULL)
    ),

    CONSTRAINT unique_pix_key_per_group UNIQUE (group_id, pix_key_value),
    CONSTRAINT participant_in_group UNIQUE (group_id, id)
);

CREATE TABLE IF NOT EXISTS expenses (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    paid_by_participant_id UUID NOT NULL,
    description VARCHAR(200) NOT NULL,
    total_cents BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    -- clock_timestamp(), not NOW(): NOW() freezes at transaction start, but writes
    -- serialize on the group lock acquired later, so NOW() can invert the causal
    -- order in the activity feed. Inserts run under the lock, so insert-time
    -- timestamps match the serialization order.
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT payer_belongs_to_group FOREIGN KEY (group_id, paid_by_participant_id)
        REFERENCES participants (group_id, id),

    CONSTRAINT positive_expense_total CHECK (total_cents > 0 AND total_cents <= 1000000000000),
    CONSTRAINT unique_expense_request UNIQUE (group_id, idempotency_key),
    CONSTRAINT expense_in_group UNIQUE (group_id, id)
);

CREATE TABLE IF NOT EXISTS expense_shares (
    expense_id UUID NOT NULL,
    group_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL,

    PRIMARY KEY (expense_id, participant_id),
    CONSTRAINT share_expense_in_group FOREIGN KEY (group_id, expense_id)
        REFERENCES expenses (group_id, id) ON DELETE CASCADE,
    CONSTRAINT share_participant_in_group FOREIGN KEY (group_id, participant_id)
        REFERENCES participants (group_id, id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT nonnegative_share CHECK (amount_cents >= 0 AND amount_cents <= 1000000000000)
);

CREATE TABLE IF NOT EXISTS settlements (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    payer_participant_id UUID NOT NULL,
    recipient_participant_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- clock_timestamp() for the same reason as expenses.created_at.
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT settlement_payer_in_group FOREIGN KEY (group_id, payer_participant_id)
        REFERENCES participants (group_id, id),
    CONSTRAINT settlement_recipient_in_group FOREIGN KEY (group_id, recipient_participant_id)
        REFERENCES participants (group_id, id),

    CONSTRAINT positive_settlement CHECK (amount_cents > 0 AND amount_cents <= 1000000000000),
    CONSTRAINT different_settlement_participants CHECK (
        payer_participant_id <> recipient_participant_id
    ),
    CONSTRAINT valid_settlement_status CHECK (
        status IN ('COMPLETED')
    ),
    CONSTRAINT unique_settlement_request UNIQUE (group_id, idempotency_key)
);
