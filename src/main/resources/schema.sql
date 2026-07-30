-- SplitPix schema (design doc v2.1, section 10).
-- Applied idempotently at startup via spring.sql.init; converted to Flyway V1__init.sql before hosting (addendum 35.4).
-- All monetary values are integer centavos (BIGINT). Floating point is never used for money.

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

    CONSTRAINT valid_pix_key_pair CHECK (
        (pix_key_type IS NULL AND pix_key_value IS NULL)
        OR
        (pix_key_type IS NOT NULL AND pix_key_value IS NOT NULL)
    ),

    CONSTRAINT unique_pix_key_per_group UNIQUE (group_id, pix_key_value)
);

CREATE TABLE IF NOT EXISTS expenses (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    paid_by_participant_id UUID NOT NULL REFERENCES participants(id),
    description VARCHAR(200) NOT NULL,
    total_cents BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT positive_expense_total CHECK (total_cents > 0),
    CONSTRAINT unique_expense_request UNIQUE (group_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS expense_shares (
    expense_id UUID NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL REFERENCES participants(id),
    amount_cents BIGINT NOT NULL,

    PRIMARY KEY (expense_id, participant_id),
    CONSTRAINT nonnegative_share CHECK (amount_cents >= 0)
);

CREATE TABLE IF NOT EXISTS settlements (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    payer_participant_id UUID NOT NULL REFERENCES participants(id),
    recipient_participant_id UUID NOT NULL REFERENCES participants(id),
    amount_cents BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT positive_settlement CHECK (amount_cents > 0),
    CONSTRAINT different_settlement_participants CHECK (
        payer_participant_id <> recipient_participant_id
    ),
    CONSTRAINT valid_settlement_status CHECK (
        status IN ('COMPLETED')
    ),
    CONSTRAINT unique_settlement_request UNIQUE (group_id, idempotency_key)
);
