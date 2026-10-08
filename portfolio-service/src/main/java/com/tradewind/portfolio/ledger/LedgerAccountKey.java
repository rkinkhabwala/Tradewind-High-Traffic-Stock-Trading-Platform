package com.tradewind.portfolio.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a ledger account: owner, type and unit. {@code symbol == null} means US dollars.
 * <p>
 * The house (the other side of every deposit, withdrawal and fill) is split into {@link #HOUSE_SHARDS} accounts per
 * unit, owned by ids {@code 00000000-0000-0000-0000-00000000000n}. Every fill credits or debits a house balance
 * row under its row lock until commit; a single house row per unit serialized all fills (about 70/s on the load
 * test machine). A posting picks its shard from a stable id (execution, deposit reference), so concurrent fills
 * rarely share a row. Shard 0 is the original single house account. Per-unit balancing and reconciliation are
 * unaffected: they sum over all accounts.
 */
public record LedgerAccountKey(UUID accountId, LedgerAccountType type, String symbol) {

    public static final int HOUSE_SHARDS = 16;
    /** House shard 0 (the house account before sharding). */
    public static final UUID HOUSE_ACCOUNT_ID = houseAccountId(0);
    public static final String USD = "USD";

    public LedgerAccountKey {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        boolean cashType = type == LedgerAccountType.CASH || type == LedgerAccountType.CASH_RESERVED;
        boolean positionType = type == LedgerAccountType.POSITION || type == LedgerAccountType.POSITION_RESERVED;
        if (cashType && symbol != null || positionType && symbol == null) {
            throw new IllegalArgumentException(type + " account has wrong unit: " + symbol);
        }
        if ((type == LedgerAccountType.HOUSE) != isHouseAccountId(accountId)) {
            throw new IllegalArgumentException("Only the house account may hold HOUSE ledger accounts");
        }
    }

    public static LedgerAccountKey cash(UUID accountId) {
        return new LedgerAccountKey(accountId, LedgerAccountType.CASH, null);
    }

    public static LedgerAccountKey cashReserved(UUID accountId) {
        return new LedgerAccountKey(accountId, LedgerAccountType.CASH_RESERVED, null);
    }

    public static LedgerAccountKey position(UUID accountId, String symbol) {
        return new LedgerAccountKey(accountId, LedgerAccountType.POSITION, symbol);
    }

    public static LedgerAccountKey positionReserved(UUID accountId, String symbol) {
        return new LedgerAccountKey(accountId, LedgerAccountType.POSITION_RESERVED, symbol);
    }

    public static UUID houseAccountId(int shard) {
        if (shard < 0 || shard >= HOUSE_SHARDS) {
            throw new IllegalArgumentException("house shard out of range: " + shard);
        }
        return new UUID(0L, shard);
    }

    public static boolean isHouseAccountId(UUID id) {
        return id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() >= 0
                && id.getLeastSignificantBits() < HOUSE_SHARDS;
    }

    static int shardFor(UUID spreadBy) {
        return Math.floorMod(spreadBy.hashCode(), HOUSE_SHARDS);
    }

    /** House cash, shard 0. */
    public static LedgerAccountKey houseCash() {
        return new LedgerAccountKey(HOUSE_ACCOUNT_ID, LedgerAccountType.HOUSE, null);
    }

    /** House cash on the shard chosen by {@code spreadBy} (e.g. the execution or deposit id). */
    public static LedgerAccountKey houseCash(UUID spreadBy) {
        return new LedgerAccountKey(houseAccountId(shardFor(spreadBy)), LedgerAccountType.HOUSE, null);
    }

    /** House shares of {@code symbol}, shard 0. */
    public static LedgerAccountKey houseShares(String symbol) {
        return new LedgerAccountKey(HOUSE_ACCOUNT_ID, LedgerAccountType.HOUSE, Objects.requireNonNull(symbol));
    }

    /** House shares of {@code symbol} on the shard chosen by {@code spreadBy}. */
    public static LedgerAccountKey houseShares(String symbol, UUID spreadBy) {
        return new LedgerAccountKey(houseAccountId(shardFor(spreadBy)), LedgerAccountType.HOUSE,
                Objects.requireNonNull(symbol));
    }

    public String unit() {
        return symbol == null ? USD : symbol;
    }

    public boolean allowNegative() {
        return type == LedgerAccountType.HOUSE;
    }
}
