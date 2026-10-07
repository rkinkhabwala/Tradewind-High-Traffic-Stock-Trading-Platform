package com.tradewind.common.arch.fixture.money;

import java.math.BigDecimal;

/** Deliberate violation used by MoneyArchitectureRulesTest. */
public class FloatConversion {
    public BigDecimal tenCents() {
        return new BigDecimal(0.1);
    }
}
