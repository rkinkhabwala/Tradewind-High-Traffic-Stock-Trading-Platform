package com.tradewind.common.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Money arithmetic for trades. All intermediate values are exact {@link BigDecimal}s; rounding to cents happens
 * at exactly one place per posting:
 * <ul>
 *   <li>Reservations round up ({@code CEILING}) so they always cover what the order can cost.</li>
 *   <li>Fills round half-even on the <em>cumulative</em> exact notional, so the sum of an order's partial fills
 *       equals the rounded total and never drifts by a cent per partial.</li>
 * </ul>
 */
public final class Notional {

    /** Market orders reserve 5% over the ask (spec section 4). */
    public static final BigDecimal MARKET_ORDER_COLLAR = new BigDecimal("0.05");

    private Notional() {
    }

    /** Exact {@code qty x price}, unrounded. */
    public static BigDecimal exact(Quantity qty, Price price) {
        return qty.value().multiply(price.value());
    }

    /** Cash to reserve for a buy: {@code qty x price x (1 + collar)}, rounded up to the cent. */
    public static Money buyReservation(Quantity qty, Price price, BigDecimal collar) {
        Objects.requireNonNull(collar, "collar");
        if (collar.signum() < 0) {
            throw new IllegalArgumentException("collar must not be negative: " + collar);
        }
        if (!qty.isPositive()) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        return Money.roundUp(exact(qty, price).multiply(BigDecimal.ONE.add(collar)));
    }

    /**
     * Cents to post for one partial fill, given the order's exact cumulative notional before and after it.
     * Summing this over every fill of an order yields {@code roundHalfEven(total exact notional)}.
     */
    public static Money fillIncrement(BigDecimal exactCumulativeBefore, BigDecimal exactCumulativeAfter) {
        return Money.roundHalfEven(exactCumulativeAfter).minus(Money.roundHalfEven(exactCumulativeBefore));
    }
}
