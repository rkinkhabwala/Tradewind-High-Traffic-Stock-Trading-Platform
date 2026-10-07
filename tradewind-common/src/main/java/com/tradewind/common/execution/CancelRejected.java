package com.tradewind.common.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code CancelRejected} v1 on {@code executions}, keyed by account id: the cancel lost the race. Reasons:
 * ALREADY_FILLED, ALREADY_CANCELED, ALREADY_EXPIRED, UNKNOWN_ORDER.
 */
public record CancelRejected(UUID orderId, UUID accountId, String symbol, String reason, Instant rejectedAt) {

    public static final int VERSION = 1;
}
