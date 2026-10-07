package com.tradewind.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityAndPriceTest {

    @Test
    void quantityIsNormalizedToSixDecimals() {
        assertThat(Quantity.of("1.5")).isEqualTo(Quantity.of("1.500000"));
        assertThat(Quantity.of("1.5").toJson()).isEqualTo("1.500000");
        assertThatThrownBy(() -> Quantity.of("0.0000001")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void quantityArithmetic() {
        Quantity q = Quantity.of("10");
        assertThat(q.minus(Quantity.of("2.25"))).isEqualTo(Quantity.of("7.75"));
        assertThat(q.plus(Quantity.of("0.000001"))).isEqualTo(Quantity.of("10.000001"));
        assertThat(q.min(Quantity.of("3"))).isEqualTo(Quantity.of("3"));
        assertThat(q.minus(q).isZero()).isTrue();
        assertThat(q.negate().isNegative()).isTrue();
    }

    @Test
    void priceIsPositiveWithFourDecimals() {
        assertThat(Price.of("187.25")).isEqualTo(Price.of(new BigDecimal("187.2500")));
        assertThat(Price.ofCents(18_725)).isEqualTo(Price.of("187.25"));
        assertThatThrownBy(() -> Price.of("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Price.of("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Price.of("1.00001")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void buyReservationRoundsUpAndAppliesCollar() {
        // 3 x 33.3333 = 99.9999 -> 100.00
        assertThat(Notional.buyReservation(Quantity.of("3"), Price.of("33.3333"), BigDecimal.ZERO))
                .isEqualTo(Money.ofCents(10_000));
        // 10 x 100.00 x 1.05 = 1050.00
        assertThat(Notional.buyReservation(Quantity.of("10"), Price.of("100"), Notional.MARKET_ORDER_COLLAR))
                .isEqualTo(Money.ofCents(105_000));
        // 0.333333 x 10.01 x 1.05 = 3.50349... -> 3.51
        assertThat(Notional.buyReservation(Quantity.of("0.333333"), Price.of("10.01"), Notional.MARKET_ORDER_COLLAR))
                .isEqualTo(Money.ofCents(351));
    }

    @Test
    void buyReservationRejectsBadInput() {
        assertThatThrownBy(() -> Notional.buyReservation(Quantity.ZERO, Price.of("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notional.buyReservation(Quantity.of("1"), Price.of("1"), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
