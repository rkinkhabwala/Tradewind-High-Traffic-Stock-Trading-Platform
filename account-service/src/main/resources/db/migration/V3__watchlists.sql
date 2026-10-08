CREATE TABLE watchlists (
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES accounts (id),
    name       VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT watchlists_name_unique UNIQUE (account_id, name)
);

CREATE TABLE watchlist_symbols (
    watchlist_id UUID        NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    symbol       VARCHAR(10) NOT NULL,
    added_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (watchlist_id, symbol)
);
