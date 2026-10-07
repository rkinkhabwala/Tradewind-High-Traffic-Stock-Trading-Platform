-- Exact cumulative notional filled so far per order: fill amounts round half-even on the cumulative value
-- (common Notional.fillIncrement), so partial fills never drift by a cent.
ALTER TABLE reservations ADD COLUMN filled_notional NUMERIC(38, 10) NOT NULL DEFAULT 0;

-- Read model (spec 5): owned shares per account and symbol (available + reserved), weighted average cost of
-- buys. Updated in the same transaction as the ledger; reconciliation checks it against the ledger.
CREATE TABLE positions_view (
    account_id UUID            NOT NULL,
    symbol     VARCHAR(10)     NOT NULL,
    qty        NUMERIC(19, 6)  NOT NULL CHECK (qty > 0),
    avg_cost   NUMERIC(24, 10) NOT NULL CHECK (avg_cost > 0),
    updated_at TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (account_id, symbol)
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
