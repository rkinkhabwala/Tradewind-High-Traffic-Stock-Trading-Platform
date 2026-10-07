package com.tradewind.execution.book;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/**
 * An open order (or market-order remainder) in a symbol's book. Immutable: each fill produces a new state.
 * {@code priceCap} is the most a buy may pay (limit, or ask x 1.05 for market buys).
 */
public record RestingOrder(UUID orderId, UUID accountId, String symbol, OrderSide side, OrderType type,
                           TimeInForce timeInForce, Price limitPrice, Price priceCap, Quantity quantity,
                           Quantity remaining, Quantity filled, int nextSeq, LocalDate sessionDate, int partition,
                           Instant createdAt) {

    /** Buys: best (highest) price first, then earliest. Market buys rank by their cap. */
    public static final Comparator<RestingOrder> BUY_PRIORITY = Comparator
            .comparing(RestingOrder::priorityPrice, Comparator.reverseOrder())
            .thenComparing(RestingOrder::createdAt)
            .thenComparing(RestingOrder::orderId);

    /** Sells: best (lowest) price first, then earliest. Market sells rank first (price 0). */
    public static final Comparator<RestingOrder> SELL_PRIORITY = Comparator
            .comparing(RestingOrder::priorityPrice)
            .thenComparing(RestingOrder::createdAt)
            .thenComparing(RestingOrder::orderId);

    public BigDecimal priorityPrice() {
        if (type == OrderType.LIMIT) {
            return limitPrice.value();
        }
        return side == OrderSide.BUY ? priceCap.value() : BigDecimal.ZERO;
    }

    /** True if a buy may execute at {@code ask}: at or below its limit, or its cap for market buys. */
    public boolean acceptsBuyAt(Price ask) {
        return ask.compareTo(type == OrderType.LIMIT ? limitPrice : priceCap) <= 0;
    }

    /** True if a sell may execute at {@code bid}: market sells always, limit sells at or above the limit. */
    public boolean acceptsSellAt(Price bid) {
        return type == OrderType.MARKET || bid.compareTo(limitPrice) >= 0;
    }

    public RestingOrder afterFill(Quantity qty) {
        return new RestingOrder(orderId, accountId, symbol, side, type, timeInForce, limitPrice, priceCap, quantity,
                remaining.minus(qty), filled.plus(qty), nextSeq + 1, sessionDate, partition, createdAt);
    }
}
