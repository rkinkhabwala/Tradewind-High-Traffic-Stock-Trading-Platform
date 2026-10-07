package com.tradewind.portfolio.ledger;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Quantity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversions between domain types and the ledger's NUMERIC(19,6) amounts. All exact. */
public final class LedgerAmounts {

    public static final int SCALE = 6;

    private LedgerAmounts() {
    }

    public static BigDecimal of(Money money) {
        return money.toDollars().setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal of(Quantity quantity) {
        return quantity.value().setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Throws if a cash balance ever holds sub-cent dollars, which would mean a posting bug. */
    public static Money toMoney(BigDecimal dollars) {
        return Money.ofDollars(dollars);
    }

    public static Quantity toQuantity(BigDecimal shares) {
        return Quantity.of(shares);
    }
}
