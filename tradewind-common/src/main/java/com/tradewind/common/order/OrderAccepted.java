package com.tradewind.common.order;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code OrderAccepted} v1 on {@code orders.submitted}, keyed by symbol. Emitted only after buying power or shares
 * were reserved. {@code priceCap} (buys only) is the highest price execution may fill at: the limit price, or for
 * market buys the ask plus the 5% collar that was reserved. Filling at or below it can never cost more than the
 * reservation.
 */
public record OrderAccepted(UUID orderId, UUID accountId, String symbol, OrderSide side, OrderType type,
                            TimeInForce timeInForce, Quantity quantity, Price limitPrice, Price priceCap,
                            Instant acceptedAt) {

    public static final int VERSION = 1;
}
