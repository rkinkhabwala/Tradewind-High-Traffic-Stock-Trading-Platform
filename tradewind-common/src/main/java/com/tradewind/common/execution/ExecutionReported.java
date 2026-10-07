package com.tradewind.common.execution;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ExecutionReported} v1 on {@code executions}, keyed by account id. {@code executionId} is unique and
 * deterministic per (order, seq), so consumers can deduplicate on it. {@code terminal} is true on the fill that
 * completes the order ({@code remainingQty == 0}).
 */
public record ExecutionReported(UUID executionId, UUID orderId, UUID accountId, String symbol, OrderSide side,
                                int seq, Quantity qty, Price price, Quantity cumulativeQty, Quantity remainingQty,
                                boolean terminal, Instant executedAt) {

    public static final int VERSION = 1;
}
