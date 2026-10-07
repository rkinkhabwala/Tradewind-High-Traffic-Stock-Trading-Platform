package com.tradewind.order.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order state machine (spec 5), enforced on every status change:
 * <pre>
 * PENDING -> ACCEPTED -> PARTIALLY_FILLED -> FILLED      (ACCEPTED -> FILLED when one fill completes the order;
 * PENDING -> REJECTED                                     PARTIALLY_FILLED -> PARTIALLY_FILLED for further partials)
 * ACCEPTED | PARTIALLY_FILLED -> CANCELED | EXPIRED
 * </pre>
 */
public enum OrderStatus {
    PENDING, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(ACCEPTED, REJECTED),
            ACCEPTED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED),
            PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED),
            FILLED, EnumSet.noneOf(OrderStatus.class),
            CANCELED, EnumSet.noneOf(OrderStatus.class),
            REJECTED, EnumSet.noneOf(OrderStatus.class),
            EXPIRED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Open orders can still fill or be canceled. */
    public boolean isOpen() {
        return this == ACCEPTED || this == PARTIALLY_FILLED;
    }

    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Illegal order transition " + from + " -> " + to);
        }
    }
}
