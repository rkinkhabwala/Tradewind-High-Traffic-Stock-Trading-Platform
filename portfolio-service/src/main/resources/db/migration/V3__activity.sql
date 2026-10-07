-- Read model for GET /api/activity (deposits, withdrawals, fills), written in the same transaction as the
-- ledger posting it describes. amount_cents is signed from the account's point of view.
CREATE TABLE activity (
    id           UUID PRIMARY KEY,
    account_id   UUID           NOT NULL,
    type         VARCHAR(10)    NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'FILL')),
    reference_id VARCHAR(128)   NOT NULL UNIQUE,
    symbol       VARCHAR(10),
    side         VARCHAR(4),
    qty          NUMERIC(19, 6),
    price        NUMERIC(19, 4),
    amount_cents BIGINT         NOT NULL,
    occurred_at  TIMESTAMPTZ    NOT NULL
);
CREATE INDEX activity_by_account_recent ON activity (account_id, occurred_at DESC, id DESC);
