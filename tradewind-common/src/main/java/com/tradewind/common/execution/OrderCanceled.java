package com.tradewind.common.execution;

import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code OrderCanceled} v1 on {@code executions}, keyed by account id. Terminal: {@code canceledQty} was never
 * filled and its reservation is released. Reasons: USER_REQUESTED, COLLAR_EXCEEDED (market buy remainder priced
 * above the reserved 5% collar).
 */
public record OrderCanceled(UUID orderId, UUID accountId, String symbol, OrderSide side, Quantity canceledQty,
                            Quantity filledQty, String reason, Instant canceledAt) {

    public static final int VERSION = 1;
}
