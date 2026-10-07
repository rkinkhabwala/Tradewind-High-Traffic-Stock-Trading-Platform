package com.tradewind.common.order;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code CancelRequested} v1 on {@code orders.submitted}, keyed by symbol: the same partition as the order's
 * {@code OrderAccepted}, so the single writer for the symbol always sees the order before its cancel.
 */
public record CancelRequested(UUID orderId, UUID accountId, String symbol, Instant requestedAt) {

    public static final int VERSION = 1;
}
