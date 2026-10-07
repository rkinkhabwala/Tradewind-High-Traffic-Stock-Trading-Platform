-- Spec 4: (account, Idempotency-Key) -> response, kept 24h. status_code IS NULL while the request is in flight.
CREATE TABLE idempotency_keys (
    account_id    UUID         NOT NULL,
    key           VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status_code   INT,
    response_json TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    claimed_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (account_id, key)
);

CREATE INDEX idempotency_keys_created_at ON idempotency_keys (created_at);
