package com.tradewind.portfolio.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** One ledger entry to write: a signed amount on one ledger account (positive increases its balance). */
public record Posting(LedgerAccountKey account, BigDecimal amount) {

    public Posting {
        Objects.requireNonNull(account, "account");
        amount = Objects.requireNonNull(amount, "amount").setScale(LedgerAmounts.SCALE, RoundingMode.UNNECESSARY);
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("zero posting on " + account);
        }
    }
}
