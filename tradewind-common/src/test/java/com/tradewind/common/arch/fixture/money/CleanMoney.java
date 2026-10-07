package com.tradewind.common.arch.fixture.money;

import java.math.BigDecimal;

/** Compliant money code used by MoneyArchitectureRulesTest. */
public class CleanMoney {
    long cents;

    public BigDecimal dollars() {
        return BigDecimal.valueOf(cents, 2);
    }
}
