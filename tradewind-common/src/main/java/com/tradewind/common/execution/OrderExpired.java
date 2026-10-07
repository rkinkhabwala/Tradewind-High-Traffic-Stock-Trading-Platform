package com.tradewind.common.execution;

import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code OrderExpired} v1 on {@code executions}, keyed by account id: a DAY order outlived its session (spec 6.5). */
public record OrderExpired(UUID orderId, UUID accountId, String symbol, OrderSide side, Quantity expiredQty,
                           Quantity filledQty, LocalDate sessionDate, Instant expiredAt) {

    public static final int VERSION = 1;
}
