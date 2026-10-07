package com.tradewind.order.order;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Notional;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.quote.QuoteTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPlanTest {

    private static QuoteTick quote(String ask) {
        Price a = Price.of(ask);
        return new QuoteTick("AAPL", Price.of(a.value().subtract(new BigDecimal("0.02"))), a, a, 100, 100, 0, Instant.EPOCH);
    }

    @Test
    void marketBuyReservesFivePercentOverAskAndCapsFillsThere() {
        ReservationPlan plan = ReservationPlan.of(OrderSide.BUY, OrderType.MARKET, Quantity.of("10"), null, quote("200.01"));

        assertThat(plan.reservedCash()).isEqualTo(Money.ofCents(210_011)); // 10 x 200.01 x 1.05 = 2100.105 -> up
        assertThat(plan.priceCap()).isEqualTo(Price.of("210.01"));          // 210.0105 -> down
    }

    @Test
    void limitBuyReservesAtTheLimitAndSellsReserveNoCash() {
        ReservationPlan limit = ReservationPlan.of(OrderSide.BUY, OrderType.LIMIT, Quantity.of("3"), Price.of("33.33"),
                quote("34.00"));
        ReservationPlan sell = ReservationPlan.of(OrderSide.SELL, OrderType.MARKET, Quantity.of("3"), null, quote("34.00"));

        assertThat(limit.reservedCash()).isEqualTo(Money.ofCents(9_999));
        assertThat(limit.priceCap()).isEqualTo(Price.of("33.33"));
        assertThat(sell.reservedCash()).isNull();
        assertThat(sell.priceCap()).isNull();
    }

    @Test
    void anyFillsAtOrBelowTheCapNeverCostMoreThanTheReservation() {
        SplittableRandom random = new SplittableRandom(11);
        for (int trial = 0; trial < 20_000; trial++) {
            Quantity qty = Quantity.of(BigDecimal.valueOf(1 + random.nextLong(100_000_000L), 6));
            QuoteTick q = quote(BigDecimal.valueOf(100 + random.nextLong(100_000), 2).toPlainString());
            OrderType type = random.nextBoolean() ? OrderType.MARKET : OrderType.LIMIT;
            Price limit = Price.of(BigDecimal.valueOf(100 + random.nextLong(100_000), 2));
            ReservationPlan plan = ReservationPlan.of(OrderSide.BUY, type, qty, limit, q);

            // Worst case for the buyer: everything fills at the cap, split into random partial fills.
            BigDecimal remaining = qty.value();
            BigDecimal cumulative = BigDecimal.ZERO;
            Money cost = Money.ZERO;
            while (remaining.signum() > 0) {
                BigDecimal part = remaining.min(BigDecimal.valueOf(1 + random.nextLong(5_000_000L), 6));
                BigDecimal next = cumulative.add(part.multiply(plan.priceCap().value()));
                cost = cost.plus(Notional.fillIncrement(cumulative, next));
                cumulative = next;
                remaining = remaining.subtract(part);
            }
            assertThat(cost).isLessThanOrEqualTo(plan.reservedCash());
        }
    }
}
