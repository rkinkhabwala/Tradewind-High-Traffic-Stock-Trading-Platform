CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    account_id          UUID           NOT NULL,
    -- The client's Idempotency-Key: the database itself refuses a second order for the same key.
    client_order_id     VARCHAR(128)   NOT NULL,
    symbol              VARCHAR(10)    NOT NULL,
    side                VARCHAR(4)     NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type                VARCHAR(6)     NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    time_in_force       VARCHAR(3)     NOT NULL CHECK (time_in_force IN ('DAY', 'GTC')),
    qty                 NUMERIC(19, 6) NOT NULL CHECK (qty > 0),
    limit_price         NUMERIC(19, 4),
    status              VARCHAR(16)    NOT NULL CHECK (status IN
        ('PENDING', 'ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED', 'EXPIRED')),
    filled_qty          NUMERIC(19, 6) NOT NULL DEFAULT 0 CHECK (filled_qty >= 0 AND filled_qty <= qty),
    avg_fill_price      NUMERIC(19, 4),
    reject_reason       VARCHAR(64),
    -- Reservation terms, fixed at placement so a retried PENDING order reserves exactly the same amount.
    reserved_cash_cents BIGINT,
    price_cap           NUMERIC(19, 4),
    cancel_requested_at TIMESTAMPTZ,
    -- Set when a PENDING order is rejected by the sweeper: its reservation (if any) must still be released.
    release_pending     BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    CONSTRAINT orders_client_order_id_unique UNIQUE (account_id, client_order_id),
    CHECK ((type = 'LIMIT') = (limit_price IS NOT NULL))
);

CREATE INDEX orders_by_account_recent ON orders (account_id, created_at DESC, id DESC);
CREATE INDEX orders_pending ON orders (created_at) WHERE status = 'PENDING';
CREATE INDEX orders_release_pending ON orders (id) WHERE release_pending;

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

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    topic        VARCHAR(64)  NOT NULL,
    event_key    VARCHAR(64)  NOT NULL,
    payload_json TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
