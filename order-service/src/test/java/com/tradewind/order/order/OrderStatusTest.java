package com.tradewind.order.order;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.tradewind.order.order.OrderStatus.ACCEPTED;
import static com.tradewind.order.order.OrderStatus.CANCELED;
import static com.tradewind.order.order.OrderStatus.EXPIRED;
import static com.tradewind.order.order.OrderStatus.FILLED;
import static com.tradewind.order.order.OrderStatus.PARTIALLY_FILLED;
import static com.tradewind.order.order.OrderStatus.PENDING;
import static com.tradewind.order.order.OrderStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every one of the 49 (from, to) pairs is checked against the spec's state machine. */
class OrderStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "PENDING->ACCEPTED", "PENDING->REJECTED",
            "ACCEPTED->PARTIALLY_FILLED", "ACCEPTED->FILLED", "ACCEPTED->CANCELED", "ACCEPTED->EXPIRED",
            "PARTIALLY_FILLED->PARTIALLY_FILLED", "PARTIALLY_FILLED->FILLED",
            "PARTIALLY_FILLED->CANCELED", "PARTIALLY_FILLED->EXPIRED");

    @Test
    void exactlyTheSpecifiedTransitionsAreAllowed() {
        int checked = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = ALLOWED.contains(from + "->" + to);
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(expected);
                if (expected) {
                    assertThatCode(() -> OrderStatus.requireTransition(from, to)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> OrderStatus.requireTransition(from, to))
                            .isInstanceOf(IllegalStateException.class);
                }
                checked++;
            }
        }
        assertThat(checked).isEqualTo(49);
    }

    @Test
    void terminalAndOpenStates() {
        assertThat(EnumSet.allOf(OrderStatus.class)).filteredOn(OrderStatus::isTerminal)
                .containsExactlyInAnyOrder(FILLED, CANCELED, REJECTED, EXPIRED);
        assertThat(EnumSet.allOf(OrderStatus.class)).filteredOn(OrderStatus::isOpen)
                .containsExactlyInAnyOrder(ACCEPTED, PARTIALLY_FILLED);
        assertThat(PENDING.isOpen()).isFalse();
    }
}
