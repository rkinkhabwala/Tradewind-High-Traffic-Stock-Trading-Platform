package com.tradewind.portfolio.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a ledger account: owner, type and unit. {@code symbol == null} means US dollars.
 * House accounts belong to {@link #HOUSE_ACCOUNT_ID}, one per unit.
 */
public record LedgerAccountKey(UUID accountId, LedgerAccountType type, String symbol) {

    public static final UUID HOUSE_ACCOUNT_ID = new UUID(0L, 0L);
    public static final String USD = "USD";

    public LedgerAccountKey {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        boolean cashType = type == LedgerAccountType.CASH || type == LedgerAccountType.CASH_RESERVED;
        boolean positionType = type == LedgerAccountType.POSITION || type == LedgerAccountType.POSITION_RESERVED;
        if (cashType && symbol != null || positionType && symbol == null) {
            throw new IllegalArgumentException(type + " account has wrong unit: " + symbol);
        }
        if ((type == LedgerAccountType.HOUSE) != HOUSE_ACCOUNT_ID.equals(accountId)) {
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

    public static LedgerAccountKey houseCash() {
        return new LedgerAccountKey(HOUSE_ACCOUNT_ID, LedgerAccountType.HOUSE, null);
    }

    public static LedgerAccountKey houseShares(String symbol) {
        return new LedgerAccountKey(HOUSE_ACCOUNT_ID, LedgerAccountType.HOUSE, Objects.requireNonNull(symbol));
    }

    public String unit() {
        return symbol == null ? USD : symbol;
    }

    public boolean allowNegative() {
        return type == LedgerAccountType.HOUSE;
    }
}
