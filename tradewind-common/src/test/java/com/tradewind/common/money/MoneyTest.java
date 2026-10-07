package com.tradewind.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void exactDollarConversionRejectsSubCentAmounts() {
        assertThat(Money.ofDollars(new BigDecimal("12.34"))).isEqualTo(Money.ofCents(1234));
        assertThat(Money.ofDollars(new BigDecimal("12.3400"))).isEqualTo(Money.ofCents(1234));
        assertThatThrownBy(() -> Money.ofDollars(new BigDecimal("12.345")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void halfEvenRoundsTiesToEvenCent() {
        assertThat(Money.roundHalfEven(new BigDecimal("0.125"))).isEqualTo(Money.ofCents(12));
        assertThat(Money.roundHalfEven(new BigDecimal("0.135"))).isEqualTo(Money.ofCents(14));
        assertThat(Money.roundHalfEven(new BigDecimal("0.1251"))).isEqualTo(Money.ofCents(13));
        assertThat(Money.roundHalfEven(new BigDecimal("-0.125"))).isEqualTo(Money.ofCents(-12));
    }

    @Test
    void roundUpAlwaysGoesTowardPositiveInfinity() {
        assertThat(Money.roundUp(new BigDecimal("0.121"))).isEqualTo(Money.ofCents(13));
        assertThat(Money.roundUp(new BigDecimal("0.12"))).isEqualTo(Money.ofCents(12));
        assertThat(Money.roundUp(new BigDecimal("-0.129"))).isEqualTo(Money.ofCents(-12));
    }

    @Test
    void arithmeticIsExactAndOverflowFails() {
        Money a = Money.ofCents(1_000);
        assertThat(a.plus(Money.ofCents(250))).isEqualTo(Money.ofCents(1_250));
        assertThat(a.minus(Money.ofCents(1_250))).isEqualTo(Money.ofCents(-250));
        assertThat(a.negate()).isEqualTo(Money.ofCents(-1_000));
        assertThatThrownBy(() -> Money.ofCents(Long.MAX_VALUE).plus(Money.ofCents(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void dollarsRoundTrip() {
        Money m = Money.ofCents(-98_765);
        assertThat(m.toDollars()).isEqualByComparingTo("-987.65");
        assertThat(Money.ofDollars(m.toDollars())).isEqualTo(m);
        assertThat(m).hasToString("-987.65 USD");
    }

    @Test
    void signPredicatesAndOrdering() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ofCents(1).isPositive()).isTrue();
        assertThat(Money.ofCents(-1).isNegative()).isTrue();
        assertThat(Money.ofCents(1)).isGreaterThan(Money.ofCents(-1));
    }
}
