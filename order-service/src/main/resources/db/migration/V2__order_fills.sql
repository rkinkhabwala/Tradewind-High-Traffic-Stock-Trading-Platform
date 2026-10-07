-- Exact cumulative qty x price of the order's fills: avg_fill_price = fill_notional / filled_qty, so the average
-- never accumulates rounding error over many partial fills.
ALTER TABLE orders ADD COLUMN fill_notional NUMERIC(38, 10) NOT NULL DEFAULT 0;
-- Highest execution seq applied; a fill with seq <= this is a duplicate.
ALTER TABLE orders ADD COLUMN last_exec_seq INT NOT NULL DEFAULT 0;

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
