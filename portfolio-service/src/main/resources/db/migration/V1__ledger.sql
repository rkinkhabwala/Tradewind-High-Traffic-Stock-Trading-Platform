-- Double-entry ledger (spec sections 4 and 5). Amounts are NUMERIC(19,6) in the account's unit:
-- US dollars when symbol IS NULL, shares of `symbol` otherwise. Every transaction balances per unit.

CREATE TABLE ledger_accounts (
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL,
    type       VARCHAR(20) NOT NULL
        CHECK (type IN ('CASH', 'CASH_RESERVED', 'POSITION', 'POSITION_RESERVED', 'HOUSE')),
    symbol     VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_accounts_unique UNIQUE NULLS NOT DISTINCT (account_id, type, symbol),
    CONSTRAINT ledger_accounts_unit_matches_type CHECK (
        (type IN ('CASH', 'CASH_RESERVED') AND symbol IS NULL)
        OR (type IN ('POSITION', 'POSITION_RESERVED') AND symbol IS NOT NULL)
        OR type = 'HOUSE')
);

CREATE TABLE ledger_txns (
    id           UUID PRIMARY KEY,
    type         VARCHAR(16)  NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'RESERVE', 'RELEASE', 'FILL')),
    reference_id VARCHAR(128) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ledger_entries (
    id                UUID PRIMARY KEY,
    txn_id            UUID          NOT NULL REFERENCES ledger_txns (id),
    ledger_account_id UUID          NOT NULL REFERENCES ledger_accounts (id),
    amount            NUMERIC(19, 6) NOT NULL CHECK (amount <> 0),
    created_at        TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ledger_entries_by_txn ON ledger_entries (txn_id);
CREATE INDEX ledger_entries_by_account ON ledger_entries (ledger_account_id);

-- Cached balance per ledger account, updated in the same transaction as its entries (optimistic locking).
CREATE TABLE balances (
    ledger_account_id UUID PRIMARY KEY REFERENCES ledger_accounts (id),
    amount            NUMERIC(19, 6) NOT NULL DEFAULT 0,
    version           BIGINT        NOT NULL DEFAULT 0,
    allow_negative    BOOLEAN       NOT NULL,
    CONSTRAINT balances_non_negative CHECK (allow_negative OR amount >= 0)
);

-- What is still reserved for each order, so fills consume it and a terminal state releases the rest.
-- A release for an unknown order leaves a CLOSED tombstone so a late reserve cannot strand money.
CREATE TABLE reservations (
    order_id         UUID PRIMARY KEY,
    account_id       UUID,
    asset            VARCHAR(16),
    reserved_amount  NUMERIC(19, 6) NOT NULL,
    remaining_amount NUMERIC(19, 6) NOT NULL CHECK (remaining_amount >= 0),
    status           VARCHAR(8)    NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL
);

-- Defense in depth: the database itself refuses a transaction that does not balance per unit.
CREATE FUNCTION assert_ledger_txn_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    unbalanced_unit TEXT;
BEGIN
    SELECT coalesce(a.symbol, 'USD')
    INTO unbalanced_unit
    FROM ledger_entries e
             JOIN ledger_accounts a ON a.id = e.ledger_account_id
    WHERE e.txn_id = NEW.txn_id
    GROUP BY coalesce(a.symbol, 'USD')
    HAVING sum(e.amount) <> 0
    LIMIT 1;
    IF unbalanced_unit IS NOT NULL THEN
        RAISE EXCEPTION 'ledger txn % does not balance for unit %', NEW.txn_id, unbalanced_unit;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT
    ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION assert_ledger_txn_balanced();

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'the ledger is append-only (% on %)', TG_OP, TG_TABLE_NAME;
END
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_txns_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_txns
    FOR EACH ROW
EXECUTE FUNCTION reject_ledger_mutation();
