package com.tradewind.order.order;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Notional;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.quote.QuoteTick;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What to reserve (spec 4) and the highest price execution may pay. Invariant for buys: every fill at or below
 * {@code priceCap} costs at most {@code qty x priceCap <= reservedCash}, so a reservation can never be overspent.
 * <ul>
 *   <li>Market buy: reserve qty x ask x 1.05 (rounded up); cap = ask x 1.05 rounded down to the cent.</li>
 *   <li>Limit buy: reserve qty x limit (rounded up); cap = limit.</li>
 *   <li>Sell: reserve qty shares; no cap.</li>
 * </ul>
 */
public record ReservationPlan(Money reservedCash, Price priceCap) {

    public static ReservationPlan of(OrderSide side, OrderType type, Quantity qty, Price limitPrice, QuoteTick quote) {
        if (side == OrderSide.SELL) {
            return new ReservationPlan(null, null);
        }
        if (type == OrderType.MARKET) {
            BigDecimal collared = quote.ask().value().multiply(BigDecimal.ONE.add(Notional.MARKET_ORDER_COLLAR));
            return new ReservationPlan(Notional.buyReservation(qty, quote.ask(), Notional.MARKET_ORDER_COLLAR),
                    Price.of(collared.setScale(2, RoundingMode.FLOOR)));
        }
        return new ReservationPlan(Notional.buyReservation(qty, limitPrice, BigDecimal.ZERO), limitPrice);
    }
}
