CREATE TABLE symbols (
    symbol     VARCHAR(10) PRIMARY KEY,
    name       VARCHAR(120)   NOT NULL,
    sector     VARCHAR(40)    NOT NULL,
    base_price NUMERIC(19, 4) NOT NULL CHECK (base_price > 0),
    -- Annualised volatility used by the price simulator (0.25 = 25%).
    volatility NUMERIC(6, 4)  NOT NULL CHECK (volatility > 0)
);

-- 1-minute OHLCV in simulated market time; coarser intervals are aggregated on read.
CREATE TABLE candles_1m (
    symbol       VARCHAR(10)    NOT NULL REFERENCES symbols (symbol),
    bucket_start TIMESTAMPTZ    NOT NULL,
    open         NUMERIC(19, 4) NOT NULL,
    high         NUMERIC(19, 4) NOT NULL,
    low          NUMERIC(19, 4) NOT NULL,
    close        NUMERIC(19, 4) NOT NULL,
    volume       BIGINT         NOT NULL,
    PRIMARY KEY (symbol, bucket_start),
    CHECK (low <= open AND low <= close AND high >= open AND high >= close)
);
