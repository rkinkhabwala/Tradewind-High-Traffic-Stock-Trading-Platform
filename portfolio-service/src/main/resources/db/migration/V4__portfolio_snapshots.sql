-- Portfolio value over time for the history chart (approved Phase 2 decision 6): written every 5 simulated
-- minutes for accounts holding anything, so reads are a cheap range scan. ts is simulated market time.
-- Phase 4 replaces the writer with a stream processor; the table and the read path stay.
CREATE TABLE portfolio_snapshots (
    account_id  UUID        NOT NULL,
    ts          TIMESTAMPTZ NOT NULL,
    value_cents BIGINT      NOT NULL,
    PRIMARY KEY (account_id, ts)
);
