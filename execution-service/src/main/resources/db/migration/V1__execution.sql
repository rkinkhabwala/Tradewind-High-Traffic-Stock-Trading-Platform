-- Open orders owned by this service (spec 5), the source the in-memory book is rebuilt from.
-- Market-order remainders rest here too (type MARKET, filled on later ticks within price_cap).
CREATE TABLE resting_orders (
    order_id        UUID PRIMARY KEY,
    account_id      UUID           NOT NULL,
    symbol          VARCHAR(10)    NOT NULL,
    side            VARCHAR(4)     NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type            VARCHAR(6)     NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    time_in_force   VARCHAR(3)     NOT NULL CHECK (time_in_force IN ('DAY', 'GTC')),
    limit_price     NUMERIC(19, 4),
    price_cap       NUMERIC(19, 4),
    quantity        NUMERIC(19, 6) NOT NULL CHECK (quantity > 0),
    remaining_qty   NUMERIC(19, 6) NOT NULL CHECK (remaining_qty > 0),
    filled_qty      NUMERIC(19, 6) NOT NULL CHECK (filled_qty >= 0),
    next_seq        INT            NOT NULL,
    session_date    DATE           NOT NULL,
    -- orders.submitted partition the order arrived on: the unit of ownership on rebalance.
    kafka_partition INT            NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (remaining_qty + filled_qty = quantity)
);
CREATE INDEX resting_orders_by_partition ON resting_orders (kafka_partition);

CREATE TABLE executions (
    id          UUID PRIMARY KEY,
    order_id    UUID           NOT NULL,
    seq         INT            NOT NULL,
    account_id  UUID           NOT NULL,
    symbol      VARCHAR(10)    NOT NULL,
    side        VARCHAR(4)     NOT NULL,
    qty         NUMERIC(19, 6) NOT NULL CHECK (qty > 0),
    price       NUMERIC(19, 4) NOT NULL CHECK (price > 0),
    executed_at TIMESTAMPTZ    NOT NULL,
    UNIQUE (order_id, seq)
);

-- Final outcome of every order this service has finished, so a late cancel can say why it lost.
CREATE TABLE closed_orders (
    order_id  UUID PRIMARY KEY,
    outcome   VARCHAR(8)  NOT NULL CHECK (outcome IN ('FILLED', 'CANCELED', 'EXPIRED')),
    closed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    topic        VARCHAR(64) NOT NULL,
    event_key    VARCHAR(64) NOT NULL,
    payload_json TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
