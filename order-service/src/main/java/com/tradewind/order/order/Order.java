package com.tradewind.order.order;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;

import java.time.Instant;
import java.util.UUID;

/** An order row. {@code reservedCashCents}/{@code priceCap} are set for buys, null for sells. */
public record Order(UUID id, UUID accountId, String clientOrderId, String symbol, OrderSide side, OrderType type,
                    TimeInForce timeInForce, Quantity quantity, Price limitPrice, OrderStatus status,
                    Quantity filledQuantity, Price avgFillPrice, String rejectReason, Long reservedCashCents,
                    Price priceCap, Instant cancelRequestedAt, Instant createdAt, Instant updatedAt) {
}
