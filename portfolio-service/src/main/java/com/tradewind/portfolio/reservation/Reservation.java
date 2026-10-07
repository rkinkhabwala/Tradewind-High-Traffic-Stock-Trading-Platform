package com.tradewind.portfolio.reservation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Buying power or shares held for one order. {@code asset} is "USD" or a symbol; amounts are in that unit.
 * {@code filledNotional} is the exact cumulative qty x price filled so far.
 * A CLOSED row with no account is a tombstone left by a release that arrived before any reserve.
 */
public record Reservation(UUID orderId, UUID accountId, String asset, BigDecimal reservedAmount,
                          BigDecimal remainingAmount, BigDecimal filledNotional, Status status) {

    public enum Status { OPEN, CLOSED }

    public boolean isTombstone() {
        return accountId == null;
    }
}
